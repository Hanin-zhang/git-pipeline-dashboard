package com.githublook.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.githublook.github.GithubApiClient;
import com.githublook.run.log.LogDigest;
import com.githublook.run.log.LogDigestCodec;
import com.githublook.run.log.LogDigestParser;
import com.githublook.run.mapper.RunJobMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 运行失败后自动判定失败原因，并把结论落库。
 *
 * <p>为什么需要它：失败原因只能从日志里推出来，而日志要么当场拉、要么永远不拉。
 * 用户点开抽屉算是「手动触发」，覆盖率取决于人的耐心；这里补上「失败即归类」。
 *
 * <p>三条约束：
 * <ul>
 *   <li>异步执行 —— 绝不在 webhook 消费线程里下载几 MB 日志</li>
 *   <li>限并发 —— 信号量 3，避免集中失败时把 GitHub 限流打爆</li>
 *   <li>失败静默 —— 归类失败只记日志，不能让运行本身显示为异常</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunFailureClassifier {

    private static final Set<String> FAILED_CONCLUSIONS =
            Set.of("failure", "timed_out", "startup_failure");
    private static final int MAX_CONCURRENT = 3;

    private final GithubApiClient github;
    private final RunJobMapper jobMapper;
    private final RunWriteService runWriteService;
    private final LogDigestCodec codec;

    private final Semaphore permits = new Semaphore(MAX_CONCURRENT);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** 事件驱动入口：失败完成时调用，不阻塞调用方。 */
    public void classifyAsync(WorkflowRun run) {
        if (run == null || run.getId() == null || !github.isConfigured()) {
            return;
        }
        if (hasText(run.getFailureClass())) {
            return;
        }
        executor.submit(() -> {
            try {
                classify(run);
            } catch (RuntimeException e) {
                log.warn("failure_classify_failed run={} error={}", run.getId(), e.toString());
            }
        });
    }

    /** 同步归类，异常一律吞掉。 */
    public String classify(WorkflowRun run) {
        return classify(run, false);
    }

    /**
     * 同步归类。
     *
     * @param force true 时忽略「已有归类结果」，强制重新下载并重算（回刷任务用）
     */
    public String classify(WorkflowRun run, boolean force) {
        if (run == null || run.getId() == null) {
            return null;
        }
        if (!force && hasText(run.getFailureClass())) {
            return run.getFailureClass();
        }
        try {
            return classifyOrThrow(run, force);
        } catch (RuntimeException e) {
            log.warn("failure_classify_error run={} error={}", run.getId(), e.toString());
            return null;
        }
    }

    /**
     * 同上，但异常向上抛。
     *
     * <p>回刷任务靠它识别「401 / 403」这类会导致整轮白跑的错误，及时中止。
     */
    String classifyOrThrow(WorkflowRun run, boolean force) {
        RunJob failedJob = firstFailedJob(run.getId());
        if (failedJob == null || failedJob.getExternalJobId() == null) {
            log.debug("failure_classify_skipped run={} reason=no-failed-job-with-id", run.getId());
            return null;
        }

        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(30, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("failure_classify_dropped run={} reason=too-many-concurrent", run.getId());
                return null;
            }
            String raw = github.fetchJobLogs(run.getRepoFullName(), failedJob.getExternalJobId());
            LogDigest digest = LogDigestParser.parse(raw);
            if (!"failure".equals(digest.verdict())) {
                log.info("failure_classify_inconclusive run={} verdict={}", run.getId(), digest.verdict());
                return null;
            }
            String failureClass = save(run, failedJob.getName(), digest);
            if (failureClass != null) {
                log.info("failure_classified run={} class={} job={} force={}", run.getId(), failureClass,
                        failedJob.getName(), force);
            }
            return failureClass;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }

    /**
     * 把一份已经算好的摘要异步落库。
     *
     * <p>用户点开日志时摘要已经是现成的，没必要让响应链路等一次 UPDATE；
     * 丢到虚拟线程里写，接口立刻返回。
     */
    public void saveAsync(WorkflowRun run, String jobName, LogDigest digest) {
        if (run == null || run.getId() == null || digest == null) {
            return;
        }
        executor.submit(() -> {
            try {
                save(run, jobName, digest);
            } catch (RuntimeException e) {
                log.warn("failure_digest_save_failed run={} error={}", run.getId(), e.toString());
            }
        });
    }

    /** @return 写入的类别；日志里没有可用的失败原因时返回 null 且不写库 */
    private String save(WorkflowRun run, String jobName, LogDigest digest) {
        if (!"failure".equals(digest.verdict())) {
            return null;
        }
        String failureClass = digest.failureClass();
        if (failureClass == null || failureClass.isBlank() || "none".equals(failureClass)) {
            return null;
        }
        runWriteService.updateFailureClassification(run.getId(), failureClass, jobName,
                digest.headline(), codec.encode(digest), LogDigestCodec.VERSION);
        return failureClass;
    }

    private RunJob firstFailedJob(Long runPk) {
        return jobMapper.selectList(new LambdaQueryWrapper<RunJob>()
                        .eq(RunJob::getRunPk, runPk)).stream()
                .filter(job -> FAILED_CONCLUSIONS.contains(job.getConclusion()))
                .findFirst()
                .orElse(null);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
