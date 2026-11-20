package com.githublook.stats.dto;

/** Per-workflow health: who is noisy, who is slow. */
public record WorkflowHealthDto(
        String workflowName,
        long total,
        long failed,
        double failureRate,
        long avgDurationMs) {
}
