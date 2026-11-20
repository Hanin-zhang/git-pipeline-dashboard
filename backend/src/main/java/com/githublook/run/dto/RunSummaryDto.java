package com.githublook.run.dto;

import java.time.LocalDateTime;
import java.util.List;

public record RunSummaryDto(
        Long id,
        Long externalRunId,
        String repoFullName,
        String workflowName,
        Integer runNumber,
        String branch,
        String eventName,
        String status,
        String conclusion,
        String actor,
        String commitSha,
        String commitMessage,
        String commitAuthor,
        String htmlUrl,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        Long durationMs,
        /** Compact job-level view so the list can draw a mini pipeline without a second request. */
        List<StageBrief> stages) {

    public record StageBrief(
            Long id,
            String name,
            String status,
            String conclusion,
            Long durationMs) {
    }
}
