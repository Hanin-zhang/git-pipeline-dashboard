package com.githublook.stats;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.githublook.common.config.AppProperties;
import com.githublook.common.time.TimeMapper;
import com.githublook.repo.mapper.MonitoredRepoMapper;
import com.githublook.run.WorkflowRun;
import com.githublook.run.mapper.WorkflowRunMapper;
import com.githublook.stats.dto.DashboardSummaryDto;
import com.githublook.stats.dto.MetricWindow;
import com.githublook.stats.dto.TrendPointDto;
import com.githublook.stats.dto.WorkflowHealthDto;
import com.githublook.stats.model.AggregateRow;
import com.githublook.stats.model.DailyStatRow;
import com.githublook.stats.model.WorkflowStatRow;
import com.githublook.stream.SseHub;
import com.githublook.webhook.DeliveryAuditService;
import com.githublook.webhook.WebhookQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read side of the dashboard. Aggregations are computed by SQL, cached in Redis with a short TTL,
 * and invalidated by version bump whenever a run is written.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");

    private final WorkflowRunMapper runMapper;
    private final MonitoredRepoMapper repoMapper;
    private final DeliveryAuditService audit;
    private final WebhookQueue queue;
    private final SseHub sseHub;
    private final StatsCache cache;
    private final TimeMapper time;
    private final AppProperties props;

    public DashboardSummaryDto summary(String repo, String branch) {
        String suffix = "summary:" + nz(repo) + ":" + nz(branch);
        Optional<DashboardSummaryDto> cached = cache.get(suffix, DashboardSummaryDto.class,
                props.getStats().getCacheTtl());
        if (cached.isPresent()) {
            return cached.get();
        }

        LocalDate today = time.today();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();
        LocalDateTime yesterdayStart = today.minusDays(1).atStartOfDay();

        AggregateRow todayRow = runMapper.aggregate(todayStart, tomorrowStart, blankToNull(repo), blankToNull(branch));
        AggregateRow yesterdayRow = runMapper.aggregate(yesterdayStart, todayStart, blankToNull(repo), blankToNull(branch));

        long active = countActive(repo, branch);
        long repoCount = Optional.ofNullable(repoMapper.selectCount(
                new LambdaQueryWrapper<com.githublook.repo.MonitoredRepo>()
                        .eq(com.githublook.repo.MonitoredRepo::getEnabled, true))).orElse(0L);
        long deliveries = audit.countSince(todayStart);

        DashboardSummaryDto dto = new DashboardSummaryDto(
                DashboardSummaryDto.toWindow(todayRow),
                DashboardSummaryDto.toWindow(yesterdayRow),
                active,
                repoCount,
                Math.max(queue.size(), 0),
                sseHub.connectionCount(),
                deliveries,
                lastEventAt(),
                LocalDateTime.now(time.zone()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        cache.put(suffix, dto, props.getStats().getCacheTtl());
        return dto;
    }

    /**
     * @param granularity {@code day} (default) or {@code week}; weeks are aggregated from the
     *                    daily rows in Java so the SQL stays identical on MySQL and H2.
     */
    public List<TrendPointDto> trend(int days, String repo, String branch, String granularity) {
        int window = Math.min(Math.max(days, 1), 90);
        String suffix = "trend:" + window + ":" + nz(repo) + ":" + nz(branch) + ":" + nz(granularity);
        Optional<TrendPointDto[]> cached = cache.get(suffix, TrendPointDto[].class, props.getStats().getCacheTtl());
        if (cached.isPresent()) {
            return List.of(cached.get());
        }

        LocalDate today = time.today();
        LocalDate from = today.minusDays(window - 1L);
        List<DailyStatRow> rows = runMapper.dailyStats(
                from.atStartOfDay(), today.plusDays(1).atStartOfDay(), blankToNull(repo), blankToNull(branch));

        Map<LocalDate, DailyStatRow> byDay = new LinkedHashMap<>();
        for (DailyStatRow row : rows) {
            if (row.getDay() != null) {
                byDay.put(row.getDay(), row);
            }
        }

        List<TrendPointDto> points = "week".equalsIgnoreCase(granularity)
                ? aggregateByWeek(from, today, byDay)
                : listByDay(from, today, byDay);

        cache.put(suffix, points, props.getStats().getCacheTtl());
        return points;
    }

    public List<WorkflowHealthDto> workflowHealth(int days, String repo, String branch) {
        int window = Math.min(Math.max(days, 1), 90);
        LocalDate today = time.today();
        List<WorkflowStatRow> rows = runMapper.workflowStats(
                today.minusDays(window - 1L).atStartOfDay(),
                today.plusDays(1).atStartOfDay(),
                blankToNull(repo), blankToNull(branch), 8);

        List<WorkflowHealthDto> result = new ArrayList<>(rows.size());
        for (WorkflowStatRow row : rows) {
            double failureRate = row.getTotal() == 0 ? 0
                    : Math.round(row.getFailedCount() * 1000d / row.getTotal()) / 10d;
            long avg = row.getAvgDurationMs() == null ? 0 : Math.round(row.getAvgDurationMs());
            result.add(new WorkflowHealthDto(row.getWorkflowName(), row.getTotal(),
                    row.getFailedCount(), failureRate, avg));
        }
        return result;
    }

    public List<String> branchOptions() {
        return runMapper.distinctBranches(50);
    }

    private List<TrendPointDto> listByDay(LocalDate from, LocalDate to, Map<LocalDate, DailyStatRow> byDay) {
        List<TrendPointDto> points = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            DailyStatRow row = byDay.get(day);
            points.add(toPoint(day.format(DAY_KEY), day.format(DAY_LABEL), row));
        }
        return points;
    }

    private List<TrendPointDto> aggregateByWeek(LocalDate from, LocalDate to, Map<LocalDate, DailyStatRow> byDay) {
        WeekFields iso = WeekFields.ISO;
        Map<String, long[]> buckets = new LinkedHashMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            int week = day.get(iso.weekOfWeekBasedYear());
            int year = day.get(iso.weekBasedYear());
            String key = year + "-W" + (week < 10 ? "0" + week : week);
            long[] acc = buckets.computeIfAbsent(key, k -> new long[]{0, 0, 0, 0, 0});
            DailyStatRow row = byDay.get(day);
            if (row != null) {
                long finished = row.getSuccessCount() + row.getFailedCount();
                acc[0] += row.getTotal();
                acc[1] += row.getSuccessCount();
                acc[2] += row.getFailedCount();
                acc[3] += Math.round(row.getAvgDurationMs() == null ? 0 : row.getAvgDurationMs()) * finished;
                acc[4] += finished;
            }
        }

        List<TrendPointDto> points = new ArrayList<>(buckets.size());
        buckets.forEach((key, acc) -> {
            long finished = acc[1] + acc[2];
            double rate = finished == 0 ? 0 : Math.round(acc[1] * 1000d / finished) / 10d;
            long avg = acc[4] == 0 ? 0 : acc[3] / acc[4];
            points.add(new TrendPointDto(key, key, acc[0], acc[1], acc[2], rate, avg));
        });
        return points;
    }

    private TrendPointDto toPoint(String key, String label, DailyStatRow row) {
        if (row == null) {
            return new TrendPointDto(key, label, 0, 0, 0, 0d, 0);
        }
        long finished = row.getSuccessCount() + row.getFailedCount();
        double rate = finished == 0 ? 0 : Math.round(row.getSuccessCount() * 1000d / finished) / 10d;
        long avg = row.getAvgDurationMs() == null ? 0 : Math.round(row.getAvgDurationMs());
        return new TrendPointDto(key, label, row.getTotal(), row.getSuccessCount(),
                row.getFailedCount(), rate, avg);
    }

    private long countActive(String repo, String branch) {
        LambdaQueryWrapper<WorkflowRun> wrapper = new LambdaQueryWrapper<WorkflowRun>()
                .in(WorkflowRun::getStatus, "queued", "in_progress", "requested", "waiting", "pending");
        if (blankToNull(repo) != null) {
            wrapper.eq(WorkflowRun::getRepoFullName, repo);
        }
        if (blankToNull(branch) != null) {
            wrapper.eq(WorkflowRun::getBranch, branch);
        }
        return Optional.ofNullable(runMapper.selectCount(wrapper)).orElse(0L);
    }

    private LocalDateTime lastEventAt() {
        WorkflowRun latest = runMapper.selectOne(new LambdaQueryWrapper<WorkflowRun>()
                .orderByDesc(WorkflowRun::getUpdatedAt)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getUpdatedAt();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nz(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }

}
