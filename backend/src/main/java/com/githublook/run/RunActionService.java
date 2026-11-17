package com.githublook.run;

import com.githublook.common.error.AppException;
import com.githublook.common.error.ErrorCode;
import com.githublook.github.GithubApiClient;
import com.githublook.run.mapper.WorkflowRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 对外的「操作类」动作：重跑、取消。
 *
 * <p>只有真的调通了 GitHub API 才返回成功；本地状态不做任何乐观修改 ——
 * 新状态由 GitHub 的 webhook 回流后自然更新，避免看板与真实状态不一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunActionService {

    private final WorkflowRunMapper runMapper;
    private final GithubApiClient github;

    public record ActionResult(
            Long runId,
            String action,
            String message,
            String githubRunUrl) {
    }

    public ActionResult rerun(Long runId) {
        WorkflowRun run = require(runId);
        if (!run.isFinished()) {
            throw new AppException(ErrorCode.CONFLICT, "运行尚未结束，无法重新运行");
        }
        github.rerun(run.getRepoFullName(), run.getExternalRunId());
        log.info("run_rerun_requested repo={} run={}", run.getRepoFullName(), run.getExternalRunId());
        return new ActionResult(run.getId(), "rerun",
                "已请求 GitHub 重新运行，新运行会在 webhook 回流后出现在列表中",
                run.getHtmlUrl());
    }

    public ActionResult cancel(Long runId) {
        WorkflowRun run = require(runId);
        if (run.isFinished()) {
            throw new AppException(ErrorCode.CONFLICT, "运行已结束，无需取消");
        }
        github.cancel(run.getRepoFullName(), run.getExternalRunId());
        log.info("run_cancel_requested repo={} run={}", run.getRepoFullName(), run.getExternalRunId());
        return new ActionResult(run.getId(), "cancel",
                "已请求 GitHub 取消该运行，状态会随 webhook 更新",
                run.getHtmlUrl());
    }

    private WorkflowRun require(Long runId) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) {
            throw AppException.notFound("运行记录");
        }
        return run;
    }
}
