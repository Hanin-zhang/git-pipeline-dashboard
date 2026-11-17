package com.githublook.run.dto;

import java.time.LocalDate;
import java.util.Set;

/** Parsed, validated query for the pipelines table. */
public record RunQuery(
        long page,
        long size,
        String statusGroup,
        String repo,
        String branch,
        String keyword,
        LocalDate from,
        LocalDate to,
        /** Exact workflow name, used by the run detail page to load its recent history. */
        String workflow) {

    private static final Set<String> STATUS_GROUPS =
            Set.of("all", "success", "failure", "running", "cancelled");

    public RunQuery {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 100);
        statusGroup = statusGroup == null || statusGroup.isBlank() ? "all" : statusGroup.toLowerCase();
        if (!STATUS_GROUPS.contains(statusGroup)) {
            statusGroup = "all";
        }
        repo = blankToNull(repo);
        branch = blankToNull(branch);
        keyword = blankToNull(keyword);
        workflow = blankToNull(workflow);
    }

    public String normalizedStatusGroup() {
        return statusGroup;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
