package com.githublook.stats.dto;

import com.githublook.stats.model.AggregateRow;

import java.time.LocalDateTime;

public record DashboardSummaryDto(
        MetricWindow today,
        MetricWindow yesterday,
        long activeRuns,
        long repoCount,
        long queueDepth,
        int liveConnections,
        long deliveriesToday,
        LocalDateTime lastEventAt,
        String generatedAt) {

    public static MetricWindow toWindow(AggregateRow row) {
        if (row == null) {
            return new MetricWindow(0, 0, 0, 0, 0d, 0);
        }
        long finished = row.getSuccessCount() + row.getFailedCount();
        double rate = finished == 0 ? 0d : Math.round(row.getSuccessCount() * 1000d / finished) / 10d;
        long avg = row.getAvgDurationMs() == null ? 0 : Math.round(row.getAvgDurationMs());
        return new MetricWindow(row.getTotal(), row.getSuccessCount(), row.getFailedCount(),
                row.getRunningCount(), rate, avg);
    }
}
