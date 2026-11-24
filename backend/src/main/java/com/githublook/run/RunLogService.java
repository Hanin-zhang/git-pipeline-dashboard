package com.githublook.run;

import com.githublook.common.error.AppException;
import com.githublook.github.GithubApiClient;
import com.githublook.run.log.LogDigest;
import com.githublook.run.log.LogDigestCodec;
import com.githublook.run.log.LogDigestParser;
import com.githublook.run.mapper.RunJobMapper;
import com.githublook.run.mapper.WorkflowRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行日志与摘要。
 *
 * <p>读取顺序是三层：
 * <ol>
 *   <li><b>数据库摘要</b>（{@code failure_digest}，含解析规则版本校验）——跨重启、跨设备，最常见</li>
 *   <li><b>进程内缓存</b>（5 分钟、最多 32 条）——同一分钟内连点不重复请求</li>
 *   <li><b>GitHub Logs API</b>——只有前两层都没有才回源</li>
 * </ol>
 *
 * <p>回源拿到摘要后<b>异步</b>写回数据库，接口不等 UPDATE。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunLogService {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final java.util.Set<String> FAILED_CONCLUSIONS =
            java.util.Set.of("failure", "timed_out", "startup_failure");
    private static final int MAX_ENTRIES = 32;

    private record Cached(String body, Instant at) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private final WorkflowRunMapper runMapper;
    private final RunJobMapper jobMapper;
    private final GithubApiClient github;
    private final LogDigestCodec codec;
    private final RunFailureClassifier classifier;

    /** 纯文本日志（全文视图用）。 */
    public String jobLogs(Long runId, Long jobId) {
        WorkflowRun run = requireRun(runId);
        RunJob job = requireJob(run, jobId);
        return fetchText(run, job);
    }

    /**
     * 结构化摘要。命中数据库时完全不打 GitHub。
     * 未命中则回源解析，并把结果异步落库供下次使用。
     */
    public LogDigest jobLogSummary(Long runId, Long jobId) {
        WorkflowRun run = requireRun(runId);
        RunJob job = requireJob(run, jobId);

        // 摘要挂在 run 上，但内容是「那个失败 job」的。打开别的 job 时必须回源取它自己的日志，
        // 否则会把失败 job 的结论错误地显示在一个通过的 job 上。
        boolean ownsDigest = ownsDigest(run, job);

        Optional<LogDigest> stored = ownsDigest
                ? codec.decode(run.getFailureDigest(), run.getFailureDigestVersion())
                : Optional.empty();
        if (stored.isPresent()) {
            log.debug("digest_hit_db run={} class={}", runId, stored.get().failureClass());
            return stored.get();
        }

        LogDigest digest = LogDigestParser.parse(fetchText(run, job));
        if (ownsDigest) {
            // 落库交给虚拟线程，接口不等待
            classifier.saveAsync(run, job.getName(), digest);
        }
        return digest;
    }

    /** 这份摘要是否属于该 job（只有失败 job 的摘要才会落库到 run 上） */
    private static boolean ownsDigest(WorkflowRun run, RunJob job) {
        String owner = run.getFailureJobName();
        if (owner != null && !owner.isBlank()) {
            return owner.equals(job.getName());
        }
        return job.getConclusion() != null && FAILED_CONCLUSIONS.contains(job.getConclusion());
    }

    private WorkflowRun requireRun(Long runId) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) {
            throw AppException.notFound("运行记录");
        }
        return run;
    }

    private RunJob requireJob(WorkflowRun run, Long jobId) {
        RunJob job = jobMapper.selectById(jobId);
        if (job == null || !run.getId().equals(job.getRunPk())) {
            throw AppException.notFound("作业记录");
        }
        return job;
    }

    private String fetchText(WorkflowRun run, RunJob job) {
        if (job.getExternalJobId() == null) {
            throw AppException.badRequest("该作业缺少 GitHub Job ID，无法拉取日志（可先点「同步 Job 明细」）");
        }
        String key = run.getRepoFullName() + "#" + job.getExternalJobId();
        Cached hit = cache.get(key);
        if (hit != null && hit.at().plus(TTL).isAfter(Instant.now())) {
            log.debug("job_logs_cache_hit key={}", key);
            return hit.body();
        }

        String body = github.fetchJobLogs(run.getRepoFullName(), job.getExternalJobId());
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(key, new Cached(body, Instant.now()));
        return body;
    }
}
