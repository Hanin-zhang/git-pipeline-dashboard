package com.githublook.stats.model;

import lombok.Getter;
import lombok.Setter;

/** Row of the per-day aggregation used by the trend chart. */
@Getter
@Setter
public class DailyStatRow {
    private java.time.LocalDate day;
    private long total;
    private long successCount;
    private long failedCount;
    private long cancelledCount;
    private Double avgDurationMs;
}
