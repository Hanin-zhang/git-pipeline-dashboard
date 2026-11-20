package com.githublook;

import com.githublook.common.error.ApiError;
import com.githublook.common.error.ErrorCode;
import com.githublook.common.web.PageResponse;
import com.githublook.run.WorkflowRun;
import com.githublook.run.dto.RunQuery;
import com.githublook.stats.dto.DashboardSummaryDto;
import com.githublook.stats.model.AggregateRow;
import com.githublook.webhook.QueueMessage;
import com.githublook.webhook.payload.GithubCommit;
import com.githublook.webhook.payload.PushEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("领域模型与协议对象")
class DomainModelTest {

    @Nested
    @DisplayName("WorkflowRun 状态判定")
    class RunState {

        private WorkflowRun run(String status, String conclusion) {
            WorkflowRun run = new WorkflowRun();
            run.setStatus(status);
            run.setConclusion(conclusion);
            return run;
        }

        @ParameterizedTest
        @DisplayName("只有 status=completed 且 conclusion 非空才算结束（- 代表 null）")
        @CsvSource({
                "completed, success, true",
                "completed, failure, true",
                "completed, -,       false",
                "in_progress, -,     false",
                "queued, -,          false"
        })
        void isFinished(String status, String conclusion, boolean expected) {
            String normalized = "-".equals(conclusion) ? null : conclusion;

            assertThat(run(status, normalized).isFinished()).isEqualTo(expected);
        }

        @ParameterizedTest
        @DisplayName("failure / timed_out / startup_failure 都算失败，cancelled 不算")
        @ValueSource(strings = {"failure", "timed_out", "startup_failure"})
        void countsFailureConclusions(String conclusion) {
            assertThat(run("completed", conclusion).isFailed()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"success", "cancelled", "skipped", "neutral"})
        void doesNotCountOtherConclusionsAsFailure(String conclusion) {
            assertThat(run("completed", conclusion).isFailed()).isFalse();
        }

        @Test
        @DisplayName("conclusion 为空时不算失败（避免把运行中误报为失败）")
        void nullConclusionIsNotFailure() {
            assertThat(run("in_progress", null).isFailed()).isFalse();
        }
    }

    @Nested
    @DisplayName("PushEvent")
    class Push {

        @Test
        @DisplayName("refs/heads/x → x；refs/tags/v1 → v1；裸分支名原样返回")
        void extractsBranchName() {
            assertThat(new PushEvent("refs/heads/main", null, null, null, null, null,
                    null, null, null, null, null, null).branchName()).isEqualTo("main");
            assertThat(new PushEvent("refs/heads/feature/a/b", null, null, null, null, null,
                    null, null, null, null, null, null).branchName()).isEqualTo("b");
            assertThat(new PushEvent("refs/tags/v1.0.0", null, null, null, null, null,
                    null, null, null, null, null, null).branchName()).isEqualTo("v1.0.0");
            assertThat(new PushEvent("main", null, null, null, null, null,
                    null, null, null, null, null, null).branchName()).isEqualTo("main");
        }

        @Test
        @DisplayName("ref 缺失时返回 null（调用方按未知分支处理）")
        void handlesMissingRef() {
            assertThat(new PushEvent(null, null, null, null, null, null,
                    null, null, null, null, null, null).branchName()).isNull();
        }

        @Test
        @DisplayName("head_commit 为 null 时时间戳返回 null，不 NPE")
        void headCommitTimestamp() {
            PushEvent empty = new PushEvent("refs/heads/main", null, null, null, null, null,
                    null, null, null, null, null, null);
            assertThat(empty.headCommitTimestamp()).isNull();

            Instant at = Instant.parse("2026-01-01T00:00:00Z");
            GithubCommit commit = new GithubCommit("sha", "msg", at, null);
            PushEvent withCommit = new PushEvent("refs/heads/main", null, "sha", null, null, null,
                    null, null, null, commit, null, null);
            assertThat(withCommit.headCommitTimestamp()).isEqualTo(at);
        }
    }

    @Nested
    @DisplayName("RunQuery 入参归一化")
    class QueryNormalization {

        @Test
        @DisplayName("页码至少为 1，页大小夹在 1..100")
        void clampsPaging() {
            assertThat(new RunQuery(0, 0, null, null, null, null, null, null, null).page()).isEqualTo(1);
            assertThat(new RunQuery(-3, 0, null, null, null, null, null, null, null).size()).isEqualTo(1);
            assertThat(new RunQuery(1, 500, null, null, null, null, null, null, null).size()).isEqualTo(100);
        }

        @Test
        @DisplayName("状态值大小写不敏感，空值/非法值退化为 all")
        void normalizesStatus() {
            assertThat(new RunQuery(1, 20, "FAILURE", null, null, null, null, null, null)
                    .normalizedStatusGroup()).isEqualTo("failure");
            assertThat(new RunQuery(1, 20, "  ", null, null, null, null, null, null)
                    .normalizedStatusGroup()).isEqualTo("all");
            assertThat(new RunQuery(1, 20, "drop-table", null, null, null, null, null, null)
                    .normalizedStatusGroup()).isEqualTo("all");
        }

        @Test
        @DisplayName("空白筛选值归一化成 null，避免生成 ='' 的恒假条件")
        void trimsBlankFilters() {
            RunQuery query = new RunQuery(1, 20, "all", "  ", "", "  ", null, null, "   ");

            assertThat(query.repo()).isNull();
            assertThat(query.branch()).isNull();
            assertThat(query.keyword()).isNull();
            assertThat(query.workflow()).isNull();
        }

        @Test
        @DisplayName("有效筛选值去除首尾空白")
        void trimsValidFilters() {
            RunQuery query = new RunQuery(1, 20, "all", " acme/app ", " main ", null, null, null, " CI ");

            assertThat(query.repo()).isEqualTo("acme/app");
            assertThat(query.branch()).isEqualTo("main");
            assertThat(query.workflow()).isEqualTo("CI");
        }
    }

    @Nested
    @DisplayName("分页信封")
    class Paging {

        @Test
        @DisplayName("totalPages 向上取整；空结果 0 页")
        void computesTotalPages() {
            assertThat(PageResponse.of(java.util.List.of(), 1, 20, 0).totalPages()).isZero();
            assertThat(PageResponse.of(java.util.List.of(), 1, 20, 105).totalPages()).isEqualTo(6);
            assertThat(PageResponse.of(java.util.List.of(), 1, 20, 100).totalPages()).isEqualTo(5);
            assertThat(PageResponse.of(java.util.List.of(), 1, 20, 1).totalPages()).isEqualTo(1);
        }

        @Test
        @DisplayName("size<=0 时不产生除零异常")
        void guardsZeroSize() {
            assertThat(PageResponse.of(java.util.List.of(), 1, 0, 10).totalPages()).isZero();
        }
    }

    @Nested
    @DisplayName("队列消息")
    class Queue {

        @Test
        @DisplayName("nextAttempt 只递增 attempt，其余字段原样保留")
        void incrementsAttempt() {
            Instant at = Instant.parse("2026-01-01T00:00:00Z");
            QueueMessage original = new QueueMessage("d-1", "workflow_run", "acme/app", "{}", 1, at);

            QueueMessage next = original.nextAttempt();

            assertThat(next.attempt()).isEqualTo(2);
            assertThat(next.deliveryId()).isEqualTo("d-1");
            assertThat(next.eventName()).isEqualTo("workflow_run");
            assertThat(next.repoFullName()).isEqualTo("acme/app");
            assertThat(next.payload()).isEqualTo("{}");
            assertThat(next.enqueuedAt()).isEqualTo(at);
        }
    }

    @Nested
    @DisplayName("统计窗口换算")
    class Window {

        @Test
        @DisplayName("null 聚合行 → 全零窗口，前端不用判空")
        void nullRowBecomesZeroWindow() {
            var window = DashboardSummaryDto.toWindow(null);

            assertThat(window.total()).isZero();
            assertThat(window.successRate()).isZero();
            assertThat(window.avgDurationMs()).isZero();
        }

        @Test
        @DisplayName("成功率保留一位小数，平均耗时四舍五入")
        void roundsRateAndDuration() {
            AggregateRow row = new AggregateRow();
            row.setTotal(3);
            row.setSuccessCount(2);
            row.setFailedCount(1);
            row.setRunningCount(0);
            row.setAvgDurationMs(1500.6);

            var window = DashboardSummaryDto.toWindow(row);

            assertThat(window.successRate()).isEqualTo(66.7);
            assertThat(window.avgDurationMs()).isEqualTo(1501);
        }

        @Test
        @DisplayName("没有结束时成功率是 0，不是 NaN")
        void noFinishedRuns() {
            AggregateRow row = new AggregateRow();
            row.setTotal(2);
            row.setRunningCount(2);
            row.setAvgDurationMs(null);

            var window = DashboardSummaryDto.toWindow(row);

            assertThat(window.successRate()).isZero();
            assertThat(window.avgDurationMs()).isZero();
        }
    }

    @Test
    @DisplayName("错误码到 HTTP 状态的映射符合接口契约")
    void errorCodeMappings() {
        assertThat(ErrorCode.SIGNATURE_INVALID.status().value()).isEqualTo(401);
        assertThat(ErrorCode.REPO_NOT_CONFIGURED.status().value()).isEqualTo(404);
        assertThat(ErrorCode.RATE_LIMITED.status().value()).isEqualTo(429);
        assertThat(ErrorCode.PAYLOAD_TOO_LARGE.status().value()).isEqualTo(413);
        assertThat(ErrorCode.CONFLICT.status().value()).isEqualTo(409);
        assertThat(ErrorCode.SERVICE_UNAVAILABLE.status().value()).isEqualTo(503);
        assertThat(ErrorCode.INTERNAL_ERROR.status().value()).isEqualTo(500);
    }

    @Test
    @DisplayName("ApiError 只暴露错误码与可读信息，不携带堆栈")
    void apiErrorShape() {
        ApiError error = ApiError.of(ErrorCode.INTERNAL_ERROR, "服务内部错误", "req-1");

        assertThat(error.error()).isEqualTo("INTERNAL_ERROR");
        assertThat(error.requestId()).isEqualTo("req-1");
        assertThat(error.timestamp()).isNotNull();
        assertThat(error.details()).isNull();
    }
}
