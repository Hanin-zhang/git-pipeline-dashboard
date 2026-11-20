package com.githublook.run.dto;

import java.time.LocalDateTime;
import java.util.List;

public record RunDetailDto(
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
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String deliveryId,
        List<JobDto> jobs) {

    public record JobDto(
            Long id,
            String name,
            String status,
            String conclusion,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            Long durationMs,
            String runnerName,
            String htmlUrl,
            /** Real GitHub Actions {@code needs}; empty when the source event carried no dependency info. */
            List<String> needs,
            List<StepDto> steps) {
    }

    public record StepDto(
            Integer number,
            String name,
            String status,
            String conclusion,
            LocalDateTime startedAt,
            LocalDateTime completedAt) {
    }
}
