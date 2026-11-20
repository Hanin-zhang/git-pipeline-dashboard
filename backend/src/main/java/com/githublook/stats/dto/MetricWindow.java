package com.githublook.stats.dto;

/** Counters for one time window. Rates and durations are already normalised for the UI. */
public record MetricWindow(
        long total,
        long success,
        long failed,
        long running,
        double successRate,
        long avgDurationMs) {
}
