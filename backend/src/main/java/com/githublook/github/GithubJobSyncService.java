package com.githublook.github;

import com.githublook.common.error.AppException;
import com.githublook.run.RunWriteService;
import com.githublook.run.WorkflowRun;
import com.githublook.run.mapper.WorkflowRunMapper;
import com.githublook.webhook.payload.GithubJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 补齐 run 的 job / step 明细。
 *
 * <p>为什么需要它：GitHub 的 `workflow_run` 事件**只带 run 级信息**（名称、状态、耗时、sha），
 * 完全没有 jobs 数组。所以看板要么显示 0 个节点，要么再调一次
 * `GET /repos/{owner}/{repo}/actions/runs/{id}/jobs` 才能把流水线画出来。
 *
 * <p>两种触发方式：
 * <ul>
 *   <li>事件驱动：收到 workflow_run 且 payload 没带 jobs 时静默补齐（失败不影响事件处理）</li>
 *   <li>手动补偿：详情页上的「同步 Job 明细」按钮，用于修复历史数据</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubJobSyncService {

    private final GithubApiClient github;
    private final RunWriteService runWriteService;
    private final WorkflowRunMapper runMapper;

    /** 手动同步：返回同步到的 job 数量。未配置 token 时抛 503。 */
    public int sync(Long runId) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) {
            throw AppException.notFound("运行记录");
        }
        if (run.getExternalRunId() == null) {
            throw AppException.badRequest("该运行缺少 GitHub Run ID，无法同步");
        }
        List<GithubJob> jobs = github.listJobs(run.getRepoFullName(), run.getExternalRunId());
        runWriteService.replaceJobs(run, jobs);
        log.info("jobs_synced run={} repo={} jobs={}", run.getId(), run.getRepoFullName(), jobs.size());
        return jobs.size();
    }

    /**
     * 事件驱动的静默补齐：只在 payload 未携带 jobs 时触发。
     * 任何失败都只记录日志，绝不让一次 API 抖动把 webhook 投递标记为失败。
     */
    public void fillIfMissing(WorkflowRun run, boolean payloadHadJobs) {
        if (payloadHadJobs || !github.isConfigured()) {
            return;
        }
        if (run.getExternalRunId() == null) {
            return;
        }
        try {
            List<GithubJob> jobs = github.listJobs(run.getRepoFullName(), run.getExternalRunId());
            if (!jobs.isEmpty()) {
                runWriteService.replaceJobs(run, jobs);
                log.info("jobs_filled_from_api run={} repo={} jobs={}",
                        run.getId(), run.getRepoFullName(), jobs.size());
            }
        } catch (RuntimeException e) {
            log.warn("jobs_fill_failed run={} repo={} reason={}",
                    run.getId(), run.getRepoFullName(), e.getMessage());
        }
    }
}
