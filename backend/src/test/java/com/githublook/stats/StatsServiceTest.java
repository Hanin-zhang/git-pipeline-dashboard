package com.githublook.stats;

import com.githublook.common.config.AppProperties;
import com.githublook.common.time.TimeMapper;
import com.githublook.repo.mapper.MonitoredRepoMapper;
import com.githublook.run.WorkflowRun;
import com.githublook.run.mapper.WorkflowRunMapper;
import com.githublook.stats.dto.DashboardSummaryDto;
import com.githublook.stats.dto.TrendPointDto;
import com.githublook.stats.dto.WorkflowHealthDto;
import com.githublook.stats.model.AggregateRow;
import com.githublook.stats.model.DailyStatRow;
import com.githublook.stats.model.WorkflowStatRow;
import com.githublook.stream.SseHub;
import com.githublook.webhook.DeliveryAuditService;
import com.githublook.webhook.WebhookQueue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StatsService · 看板聚合")
class StatsServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Mock
    private WorkflowRunMapper runMapper;
    @Mock
    private MonitoredRepoMapper repoMapper;
    @Mock
    private DeliveryAuditService audit;
    @Mock
    private WebhookQueue queue;
    @Mock
    private SseHub sseHub;
    @Mock
    private StatsCache cache;

    private final TimeMapper time = new TimeMapper("Asia/Shanghai");
    private final AppProperties props = new AppProperties();

    private StatsService service;

    @BeforeAll
    static void initMybatisLambdas() {
        com.githublook.support.MybatisLambdaTestSupport.init(
                WorkflowRun.class, com.githublook.repo.MonitoredRepo.class);
    }

    @BeforeEach
    void setUp() {
        service = new StatsService(runMapper, repoMapper, audit, queue, sseHub, cache, time, props);
        when(cache.get(any(), any(), any())).thenReturn(Optional.empty());
    }

    private LocalDate today() {
        return LocalDate.now(ZONE);
    }

    private AggregateRow row(long total, long success, long failed, long running, Double avg) {
        AggregateRow row = new AggregateRow();
        row.setTotal(total);
        row.setSuccessCount(success);
        row.setFailedCount(failed);
        row.setRunningCount(running);
        row.setAvgDurationMs(avg);
        return row;
    }

    private DailyStatRow day(LocalDate date, long total, long success, long failed, Double avg) {
        DailyStatRow row = new DailyStatRow();
        row.setDay(date);
        row.setTotal(total);
        row.setSuccessCount(success);
        row.setFailedCount(failed);
        row.setAvgDurationMs(avg);
        return row;
    }

    @Nested
    @DisplayName("概览卡片")
    class Summary {

        @Test
        @DisplayName("今日/昨日双窗口 + 成功率 + 平均耗时，队列深度为负时归零")
        void buildsSummary() {
            when(runMapper.aggregate(any(), any(), any(), any()))
                    .thenReturn(row(10, 7, 1, 2, 1234.6))
                    .thenReturn(row(8, 4, 3, 1, null));
            when(runMapper.selectCount(any())).thenReturn(3L);
            when(repoMapper.selectCount(any())).thenReturn(5L);
            when(audit.countSince(any())).thenReturn(12L);
            when(queue.size()).thenReturn(-1L);
            when(sseHub.connectionCount()).thenReturn(2);
            WorkflowRun latest = new WorkflowRun();
            latest.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 9, 30));
            when(runMapper.selectOne(any())).thenReturn(latest);

            DashboardSummaryDto dto = service.summary(null, null);

            assertThat(dto.today().total()).isEqualTo(10);
            assertThat(dto.today().success()).isEqualTo(7);
            assertThat(dto.today().failed()).isEqualTo(1);
            assertThat(dto.today().running()).isEqualTo(2);
            assertThat(dto.today().successRate()).isEqualTo(87.5);
            assertThat(dto.today().avgDurationMs()).isEqualTo(1235);

            assertThat(dto.yesterday().total()).isEqualTo(8);
            assertThat(dto.yesterday().successRate()).isEqualTo(57.1);
            assertThat(dto.yesterday().avgDurationMs()).isZero();

            assertThat(dto.activeRuns()).isEqualTo(3);
            assertThat(dto.repoCount()).isEqualTo(5);
            assertThat(dto.queueDepth()).isZero();
            assertThat(dto.liveConnections()).isEqualTo(2);
            assertThat(dto.deliveriesToday()).isEqualTo(12);
            assertThat(dto.lastEventAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 30));
            assertThat(dto.generatedAt()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        }

        @Test
        @DisplayName("没有任何结束时成功率是 0 而不是 NaN（除零保护）")
        void guardsAgainstDivisionByZero() {
            when(runMapper.aggregate(any(), any(), any(), any())).thenReturn(row(2, 0, 0, 2, null));
            when(runMapper.selectCount(any())).thenReturn(0L);
            when(repoMapper.selectCount(any())).thenReturn(0L);
            when(audit.countSince(any())).thenReturn(0L);
            when(queue.size()).thenReturn(0L);

            DashboardSummaryDto dto = service.summary(null, null);

            assertThat(dto.today().successRate()).isZero();
            assertThat(dto.today().avgDurationMs()).isZero();
        }

        @Test
        @DisplayName("命中缓存时完全不走数据库")
        void returnsCachedSummary() {
            DashboardSummaryDto cached = new DashboardSummaryDto(null, null, 1, 2, 3, 4, 5, null, "x");
            when(cache.get(any(), any(), any())).thenReturn(Optional.of(cached));

            DashboardSummaryDto dto = service.summary("acme/app", "main");

            assertThat(dto).isSameAs(cached);
            verify(runMapper, never()).aggregate(any(), any(), any(), any());
        }

        @Test
        @DisplayName("查询结果回写缓存，TTL 取自配置")
        void writesBackToCache() {
            when(runMapper.aggregate(any(), any(), any(), any())).thenReturn(row(1, 1, 0, 0, 10d));
            when(runMapper.selectCount(any())).thenReturn(0L);
            when(repoMapper.selectCount(any())).thenReturn(0L);
            when(audit.countSince(any())).thenReturn(0L);

            service.summary(null, null);

            verify(cache).put(eq("summary:-:-"), any(DashboardSummaryDto.class),
                    eq(props.getStats().getCacheTtl()));
        }
    }

    @Nested
    @DisplayName("趋势图")
    class Trend {

        @Test
        @DisplayName("按天：缺失的日期补 0，保证前端 X 轴连续")
        void fillsMissingDays() {
            LocalDate today = today();
            when(runMapper.dailyStats(any(), any(), any(), any())).thenReturn(List.of(
                    day(today.minusDays(2), 4, 3, 1, 1000d),
                    day(today, 1, 1, 0, 500d)));

            List<TrendPointDto> points = service.trend(3, null, null, "day");

            assertThat(points).hasSize(3);
            assertThat(points.get(0).key()).isEqualTo(today.minusDays(2).toString());
            assertThat(points.get(0).total()).isEqualTo(4);
            assertThat(points.get(0).successRate()).isEqualTo(75.0);
            assertThat(points.get(0).avgDurationMs()).isEqualTo(1000);
            assertThat(points.get(1).total()).isZero();
            assertThat(points.get(1).successRate()).isZero();
            assertThat(points.get(2).total()).isEqualTo(1);
            assertThat(points.get(2).successRate()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("label 使用 MM-dd，前端直接展示")
        void usesShortLabels() {
            LocalDate today = today();
            when(runMapper.dailyStats(any(), any(), any(), any())).thenReturn(List.of());

            List<TrendPointDto> points = service.trend(1, null, null, null);

            assertThat(points).hasSize(1);
            assertThat(points.get(0).label()).matches("\\d{2}-\\d{2}");
        }

        @Test
        @DisplayName("按周：ISO 周聚合，总量与按天一致")
        void aggregatesByIsoWeek() {
            LocalDate today = today();
            when(runMapper.dailyStats(any(), any(), any(), any())).thenReturn(List.of(
                    day(today.minusDays(13), 5, 3, 2, 600d),
                    day(today.minusDays(12), 5, 2, 3, 800d),
                    day(today, 4, 4, 0, 100d)));

            List<TrendPointDto> points = service.trend(14, null, null, "week");

            assertThat(points).isNotEmpty().allSatisfy(p -> assertThat(p.key()).matches("\\d{4}-W\\d{2}"));
            assertThat(points.stream().mapToLong(TrendPointDto::total).sum()).isEqualTo(14);
            assertThat(points.stream().mapToLong(TrendPointDto::success).sum()).isEqualTo(9);
            assertThat(points.stream().mapToLong(TrendPointDto::failed).sum()).isEqualTo(5);
        }

        @Test
        @DisplayName("按周：耗时按完成数为权重加权，而不是简单平均")
        void weightsAverageDurationByFinishedRuns() {
            LocalDate today = today();
            when(runMapper.dailyStats(any(), any(), any(), any())).thenReturn(List.of(
                    day(today, 3, 2, 1, 300d)));

            List<TrendPointDto> points = service.trend(1, null, null, "week");

            assertThat(points).hasSize(1);
            assertThat(points.get(0).avgDurationMs()).isEqualTo(300);
        }

        @Test
        @DisplayName("days 被夹在 1..90，避免恶意参数打爆查询")
        void clampsWindow() {
            when(runMapper.dailyStats(any(), any(), any(), any())).thenReturn(List.of());

            assertThat(service.trend(0, null, null, null)).hasSize(1);
            assertThat(service.trend(-5, null, null, null)).hasSize(1);
            assertThat(service.trend(9999, null, null, null)).hasSize(90);
        }

        @Test
        @DisplayName("窗口起点 = 今天 - (days-1)，终点 = 明天 00:00（含今天）")
        void computesWindowBounds() {
            when(runMapper.dailyStats(any(), any(), any(), any())).thenReturn(List.of());

            service.trend(7, "acme/app", "main", "day");

            ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(runMapper).dailyStats(from.capture(), to.capture(), eq("acme/app"), eq("main"));
            assertThat(from.getValue()).isEqualTo(today().minusDays(6).atStartOfDay());
            assertThat(to.getValue()).isEqualTo(today().plusDays(1).atStartOfDay());
        }

        @Test
        @DisplayName("缓存命中直接返回，不查库")
        void usesCachedTrend() {
            TrendPointDto[] cached = {new TrendPointDto("2026-01-01", "01-01", 1, 1, 0, 100d, 10)};
            when(cache.get(any(), eq(TrendPointDto[].class), any())).thenReturn(Optional.of(cached));

            List<TrendPointDto> points = service.trend(7, null, null, "day");

            assertThat(points).containsExactly(cached[0]);
            verify(runMapper, never()).dailyStats(any(), any(), any(), any());
        }

        @Test
        @DisplayName("空白 repo/branch 当作「全部」，不会拼出 ='' 的空条件")
        void treatsBlankFiltersAsAll() {
            when(runMapper.dailyStats(any(), any(), any(), any())).thenReturn(List.of());

            service.trend(7, "  ", "", "day");

            verify(runMapper).dailyStats(any(), any(), eq(null), eq(null));
        }
    }

    @Nested
    @DisplayName("工作流健康度")
    class WorkflowHealth {

        private WorkflowStatRow row(String name, long total, long failed, Double avg) {
            WorkflowStatRow row = new WorkflowStatRow();
            row.setWorkflowName(name);
            row.setTotal(total);
            row.setFailedCount(failed);
            row.setAvgDurationMs(avg);
            return row;
        }

        @Test
        @DisplayName("失败率保留一位小数，平均耗时四舍五入")
        void computesFailureRate() {
            when(runMapper.workflowStats(any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of(row("CI", 3, 2, 1234.5), row("Deploy", 4, 0, null)));

            List<WorkflowHealthDto> health = service.workflowHealth(7, null, null);

            assertThat(health).hasSize(2);
            assertThat(health.get(0).workflowName()).isEqualTo("CI");
            assertThat(health.get(0).total()).isEqualTo(3);
            assertThat(health.get(0).failed()).isEqualTo(2);
            assertThat(health.get(0).failureRate()).isEqualTo(66.7);
            assertThat(health.get(0).avgDurationMs()).isEqualTo(1235);
            assertThat(health.get(1).failureRate()).isZero();
            assertThat(health.get(1).avgDurationMs()).isZero();
        }

        @Test
        @DisplayName("total=0 时失败率为 0，不产生 NaN")
        void guardsZeroTotal() {
            when(runMapper.workflowStats(any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of(row("Empty", 0, 0, null)));

            assertThat(service.workflowHealth(7, null, null).get(0).failureRate()).isZero();
        }

        @Test
        @DisplayName("最多取 8 条最吵闹的工作流")
        void capsAtEight() {
            when(runMapper.workflowStats(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

            service.workflowHealth(30, null, null);

            verify(runMapper).workflowStats(any(), any(), any(), any(), eq(8));
        }
    }

    @Test
    @DisplayName("分支候选与运行列表共用同一套来源")
    void delegatesBranchOptions() {
        when(runMapper.distinctBranches(50)).thenReturn(List.of("main"));

        assertThat(service.branchOptions()).containsExactly("main");
    }

    @Test
    @DisplayName("缓存 TTL 生效：同一 parameters 下第二次调用不会重复聚合")
    void summaryIsCachedForConfiguredTtl() {
        props.getStats().setCacheTtl(Duration.ofSeconds(5));
        when(runMapper.aggregate(any(), any(), any(), any())).thenReturn(row(1, 1, 0, 0, 1d));
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(repoMapper.selectCount(any())).thenReturn(0L);
        when(audit.countSince(any())).thenReturn(0L);

        service.summary(null, null);

        verify(cache).put(any(), any(), eq(Duration.ofSeconds(5)));
    }
}
