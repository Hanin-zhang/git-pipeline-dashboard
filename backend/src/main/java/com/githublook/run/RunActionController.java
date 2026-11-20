package com.githublook.run;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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

    @PostMapping("/{id}/rerun")
    public ResponseEntity<RunActionService.ActionResult> rerun(@PathVariable Long id) {
        return ResponseEntity.accepted().body(runActionService.rerun(id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<RunActionService.ActionResult> cancel(@PathVariable Long id) {
        return ResponseEntity.accepted().body(runActionService.cancel(id));
    }
}
