package com.githublook.run;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行操作入口。未配置 GITHUB_TOKEN 时由 {@link RunActionService} 抛出 503，
 * 前端在按钮层面就会禁用，不会走到这里。
 */
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
public class RunActionController {

    private final RunActionService runActionService;
    private final com.githublook.github.GithubJobSyncService jobSyncService;
    private final RunLogService logService;

    @PostMapping("/{id}/rerun")
    public ResponseEntity<RunActionService.ActionResult> rerun(@PathVariable Long id) {
        return ResponseEntity.accepted().body(runActionService.rerun(id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<RunActionService.ActionResult> cancel(@PathVariable Long id) {
        return ResponseEntity.accepted().body(runActionService.cancel(id));
    }

    /** 给历史运行补 job/step 明细（真实事件不带 jobs，只能事后调 Actions API） */
    @PostMapping("/{id}/sync-jobs")
    public ResponseEntity<java.util.Map<String, Object>> syncJobs(@PathVariable Long id) {
        int count = jobSyncService.sync(id);
        return ResponseEntity.ok(java.util.Map.of(
                "runId", id,
                "jobs", count,
                "message", count > 0 ? "已同步 " + count + " 个 job" : "GitHub 返回的 job 列表为空"));
    }

    /** 代理单个 job 的执行日志（纯文本，按 2MB 截断） */
    @GetMapping(value = "/{runId}/jobs/{jobId}/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> jobLogs(@PathVariable Long runId, @PathVariable Long jobId) {
        String body = logService.jobLogs(runId, jobId);
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8))
                .body(body == null || body.isEmpty() ? "（该 job 暂无日志输出）" : body);
    }
}
