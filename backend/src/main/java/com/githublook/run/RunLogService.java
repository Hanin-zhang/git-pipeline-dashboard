package com.githublook.run;

import com.githublook.common.error.AppException;
import com.githublook.github.GithubApiClient;
import com.githublook.run.mapper.RunJobMapper;
import com.githublook.run.mapper.WorkflowRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行日志代理。
 *
 * <p>日志正文在 GitHub 侧，按 job 粒度提供（{@code /actions/jobs/{id}/logs}）。
 * 我们只做「鉴权 + 转发 + 截断」，不落库：日志体积大、时效性强，存下来意义不大。
 *
 * <p>但每次点击都打一次 GitHub 也没必要 —— 运行结束后日志就不再变化，
 * 所以加一层极短的内存缓存（5 分钟、最多 32 条）：
 * 用户来回点不会重复请求，也不会把日志常驻内存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunLogService {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_ENTRIES = 32;

    private record Cached(String body, Instant at) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private final WorkflowRunMapper runMapper;
    private final RunJobMapper jobMapper;
    private final GithubApiClient github;

    public String jobLogs(Long runId, Long jobId) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) {
            throw AppException.notFound("运行记录");
        }
        RunJob job = jobMapper.selectById(jobId);
        if (job == null || !run.getId().equals(job.getRunPk())) {
            throw AppException.notFound("作业记录");
        }
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
