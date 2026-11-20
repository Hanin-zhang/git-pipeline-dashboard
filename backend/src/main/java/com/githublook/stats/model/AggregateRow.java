package com.githublook.stats.model;

import lombok.Getter;
import lombok.Setter;

/** Aggregate counters for one time window (today / yesterday / custom range). */
@Getter
@Setter
public class AggregateRow {
    private long total;
    private long successCount;
    private long failedCount;
    private long runningCount;
    private Double avgDurationMs;
}
