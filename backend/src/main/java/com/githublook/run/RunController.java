package com.githublook.run;

import com.githublook.common.web.PageResponse;
import com.githublook.run.dto.RunDetailDto;
import com.githublook.run.dto.RunFiltersDto;
import com.githublook.run.dto.RunQuery;
import com.githublook.run.dto.RunSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
public class RunController {

    private final RunQueryService runQueryService;

    @GetMapping
    public PageResponse<RunSummaryDto> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) String repo,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String workflow,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return runQueryService.search(new RunQuery(page, size, status, repo, branch, q, from, to, workflow));
    }

    @GetMapping("/filters")
    public RunFiltersDto filters() {
        return runQueryService.filters();
    }

    @GetMapping("/{id}")
    public RunDetailDto detail(@PathVariable Long id) {
        return runQueryService.detail(id);
    }
}
