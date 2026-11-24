package com.githublook.stats;

import com.githublook.run.RunQueryService;
import com.githublook.run.dto.RunQuery;
import com.githublook.run.dto.RunSummaryDto;
import com.githublook.stats.dto.DashboardSummaryDto;
import com.githublook.stats.dto.FailureReasonStats;
import com.githublook.stats.dto.TrendPointDto;
import com.githublook.stats.dto.WorkflowHealthDto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final StatsService statsService;
    private final RunQueryService runQueryService;

    @GetMapping("/summary")
    public DashboardSummaryDto summary(@RequestParam(required = false) String repo,
                                       @RequestParam(required = false) String branch) {
        return statsService.summary(repo, branch);
    }

    @GetMapping("/trend")
    public List<TrendPointDto> trend(@RequestParam(defaultValue = "7") @Min(1) @Max(90) int days,
                                     @RequestParam(required = false) String repo,
                                     @RequestParam(required = false) String branch,
                                     @RequestParam(defaultValue = "day") String granularity) {
        return statsService.trend(days, repo, branch, granularity);
    }

    @GetMapping("/workflows")
    public List<WorkflowHealthDto> workflows(@RequestParam(defaultValue = "7") @Min(1) @Max(90) int days,
                                             @RequestParam(required = false) String repo,
                                             @RequestParam(required = false) String branch) {
        return statsService.workflowHealth(days, repo, branch);
    }

    /** 失败原因分布：本周 12 次失败里，7 次测试、4 次依赖、1 次未归类 */
    @GetMapping("/failure-reasons")
    public FailureReasonStats failureReasons(@RequestParam(defaultValue = "7") @Min(1) @Max(90) int days,
                                             @RequestParam(required = false) String repo,
                                             @RequestParam(required = false) String branch) {
        return statsService.failureReasons(days, repo, branch);
    }

    /** Initial fill for the live event stream; afterwards SSE pushes the deltas. */
    @GetMapping("/recent")
    public List<RunSummaryDto> recent(@RequestParam(defaultValue = "12") @Min(1) @Max(50) int limit) {
        return runQueryService.search(new RunQuery(1, limit, "all", null, null, null, null, null, null)).items();
    }
}
