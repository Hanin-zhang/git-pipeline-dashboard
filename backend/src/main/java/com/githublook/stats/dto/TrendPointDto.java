package com.githublook.stats.dto;

/** One bucket of the trend chart (a day, or an aggregated ISO week when granularity=week). */
public record TrendPointDto(
        String key,
        String label,
        long total,
        long success,
        long failed,
        double successRate,
        long avgDurationMs) {
}
