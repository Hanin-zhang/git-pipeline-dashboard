package com.githublook.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.githublook.common.error.AppException;
import com.githublook.github.GithubApiClient;
import com.githublook.run.log.LogDigestCodec;
import com.githublook.run.mapper.WorkflowRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 历史失败摘要回刷。
 *
 * <p>为什么需要：解析规则一升级，{@code LogDigestCodec.VERSION} 就 +1，
 * 库里旧版本的摘要随即失效。用户逐条点开虽然也能重算，但几百条历史不现实，
 * 于是补一个夜间批量任务。
 *
 * <p>安全性设计：
 * <ul>
 *   <li><b>单轮上限</b>——默认 50 条，避免一夜之间打爆 GitHub 配额</li>
 *   <li><b>单实例互斥</b>——进程内 AtomicBoolean。本项目 SSE 本身不支持多副本，
 *       所以不引入 Redis 锁也足够；将来若扩副本，这里要换成 SETNX</li>
 *   <li><b>鉴权失败立即中止</b>——token 失效时继续跑几十条毫无意义</li>
 *   <li><b>连续失败熔断</b>——连续 3 条拿不到结果就停，避免异常场景空转</li>
 * </ul>
 *
 * <p>天然幂等：待办条件就是「版本落后或从未分析」，跑完版本号就上去了，
 * 下一轮自然跳过。因此不需要记录游标，中途中断也能下次接着来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DigestBackfillService {

    /** 单轮最多处理多少条，防止一次任务把配额吃光 */
    private static final int MAX_LIMIT = 200;
    private static final int CONSECUTIVE_FAILURE_LIMIT = 3;
    private static final long THROTTLE_MS = 300L;

    public record Result(
            boolean skipped,
            String reason,
            int candidates,
            int updated,
            int failed) {
    }

    private final WorkflowRunMapper runMapper;
    private final RunFailureClassifier classifier;
    private final GithubApiClient github;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${app.digest.backfill-enabled:true}")
    private boolean enabled;

    @Value("${app.digest.backfill-limit:50}")
    private int defaultLimit;

    /** 凌晨 2:30，避开构建高峰 */
    @Scheduled(cron = "${app.digest.backfill-cron:0 30 2 * * ?}")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        Result result = run(defaultLimit);
        log.info("digest_backfill_scheduled skipped={} reason={} candidates={} updated={} failed={}",
                result.skipped(), result.reason(), result.candidates(), result.updated(), result.failed());
    }

    public Result run(int limit) {
        if (!github.isConfigured()) {
            return new Result(true, "未配置 GITHUB_TOKEN，无法拉取日志", 0, 0, 0);
        }
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        if (!running.compareAndSet(false, true)) {
            return new Result(true, "已有回刷任务在执行", 0, 0, 0);
        }
        try {
            List<WorkflowRun> candidates = findCandidates(capped);
            if (candidates.isEmpty()) {
                return new Result(false, "没有需要回刷的记录", 0, 0, 0);
            }
            log.info("digest_backfill_start candidates={} limit={}", candidates.size(), capped);

            int updated = 0;
            int failed = 0;
            int consecutive = 0;

            for (WorkflowRun run : candidates) {
                try {
                    String failureClass = classifier.classifyOrThrow(run, true);
                    if (failureClass != null) {
                        updated++;
                        consecutive = 0;
                    } else {
                        failed++;
                        consecutive++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    consecutive++;
                    log.warn("digest_backfill_item_failed run={} error={}", run.getId(), e.toString());
                    if (isAuthFailure(e)) {
                        return new Result(false,
                                "GitHub 鉴权失败，任务中止：" + e.getMessage(),
                                candidates.size(), updated, failed);
                    }
                }

                if (consecutive >= CONSECUTIVE_FAILURE_LIMIT) {
                    return new Result(false,
                            "连续 " + CONSECUTIVE_FAILURE_LIMIT + " 条无法归类，任务中止（可能是日志不可用）",
                            candidates.size(), updated, failed);
                }
                throttle();
            }

            log.info("digest_backfill_done candidates={} updated={} failed={}",
                    candidates.size(), updated, failed);
            return new Result(false, "完成", candidates.size(), updated, failed);
        } finally {
            running.set(false);
        }
    }

    /**
     * 待办条件：失败运行，且（从未分析过 或 版本落后）。
     *
     * <p>必须显式带 {@code IS NULL}——SQL 里 {@code NULL < 3} 不成立，
     * 只写版本比较会漏掉 1.5.0 时期「只存了类别、没存摘要」的记录。
     */
    private List<WorkflowRun> findCandidates(int limit) {
        return runMapper.selectList(new LambdaQueryWrapper<WorkflowRun>()
                .in(WorkflowRun::getConclusion, List.of("failure", "timed_out", "startup_failure"))
                .and(w -> w.isNull(WorkflowRun::getFailureDigest)
                        .or().lt(WorkflowRun::getFailureDigestVersion, LogDigestCodec.VERSION))
                .orderByDesc(WorkflowRun::getStartedAt)
                .last("LIMIT " + limit));
    }

    private static boolean isAuthFailure(RuntimeException e) {
        if (!(e instanceof AppException)) {
            return false;
        }
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return message.contains("401") || message.contains("403")
                || message.contains("bad credentials")
                || message.contains("鉴权") || message.contains("token");
    }

    private static void throttle() {
        try {
            Thread.sleep(THROTTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
