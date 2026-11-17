package com.githublook.stats.model;

import lombok.Getter;
import lombok.Setter;

/** Per-workflow health, used for the "noisiest pipelines" breakdown. */
@Getter
@Setter
public class WorkflowStatRow {
    private String workflowName;
    private long total;
    private long failedCount;
    private Double avgDurationMs;
}
