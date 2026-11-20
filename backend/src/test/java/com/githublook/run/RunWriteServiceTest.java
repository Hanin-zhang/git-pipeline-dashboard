package com.githublook.run;

import com.githublook.common.time.TimeMapper;
import com.githublook.run.mapper.RunJobMapper;
import com.githublook.run.mapper.RunStepMapper;
import com.githublook.run.mapper.WorkflowRunMapper;
import com.githublook.support.MybatisLambdaTestSupport;
import com.githublook.webhook.payload.GithubActor;
import com.githublook.webhook.payload.GithubCommit;
import com.githublook.webhook.payload.GithubCommitAuthor;
import com.githublook.webhook.payload.GithubJob;
import com.githublook.webhook.payload.GithubStep;
import com.githublook.webhook.payload.WorkflowRunData;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RunWriteService · 运行聚合写入")
class RunWriteServiceTest {

    @Mock
    private WorkflowRunMapper runMapper;
    @Mock
    private RunJobMapper jobMapper;
    @Mock
    private RunStepMapper stepMapper;

    private final TimeMapper time = new TimeMapper("Asia/Shanghai");
    private RunWriteService service;

    @BeforeAll
    static void initMybatisLambdas() {
        MybatisLambdaTestSupport.init(WorkflowRun.class, RunJob.class, RunStep.class);
    }

    @BeforeEach
    void setUp() {
        service = new RunWriteService(runMapper, jobMapper, stepMapper, time);
        when(jobMapper.selectList(any())).thenReturn(List.of());
        doAnswer(inv -> {
            ((WorkflowRun) inv.getArgument(0)).setId(99L);
            return 1;
        }).when(runMapper).insert(any(WorkflowRun.class));
        doAnswer(inv -> {
            ((RunJob) inv.getArgument(0)).setId(100L + ((RunJob) inv.getArgument(0)).hashCode() % 10);
            return 1;
        }).when(jobMapper).insert(any(RunJob.class));
    }

    private WorkflowRunData data(Long id, String status, String conclusion,
                                 Instant started, Instant updated, List<GithubJob> jobs) {
        return new WorkflowRunData(
                id, "CI", "main", "sha-head", 42, 1, "push", status, conclusion,
                started, started, updated,
                "https://github.com/acme/app/actions/runs/" + id,
                new GithubActor("alice", "User"),
                new GithubActor("trigger-user", "User"),
                new GithubCommit("sha-commit", "feat: something",
                        started, new GithubCommitAuthor("Alice", "a@x.io")),
                jobs);
    }

    @Test
    @DisplayName("新运行：字段映射完整，返回 created=true")
    void createsNewRun() {
        when(runMapper.selectOne(any())).thenReturn(null);

        RunWriteService.UpsertOutcome outcome = service.upsert(
                data(123L, "in_progress", null, Instant.parse("2026-01-01T10:00:00Z"), null, null),
                "acme/app", "d-1");

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.justFinished()).isFalse();

        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runMapper).insert(captor.capture());
        WorkflowRun run = captor.getValue();
        assertThat(run.getExternalRunId()).isEqualTo(123L);
        assertThat(run.getRepoFullName()).isEqualTo("acme/app");
        assertThat(run.getWorkflowName()).isEqualTo("CI");
        assertThat(run.getRunNumber()).isEqualTo(42);
        assertThat(run.getBranch()).isEqualTo("main");
        assertThat(run.getEventName()).isEqualTo("push");
        assertThat(run.getStatus()).isEqualTo("in_progress");
        assertThat(run.getActor()).isEqualTo("trigger-user");
        assertThat(run.getCommitSha()).isEqualTo("sha-head");
        assertThat(run.getCommitMessage()).isEqualTo("feat: something");
        assertThat(run.getCommitAuthor()).isEqualTo("Alice");
        assertThat(run.getDeliveryId()).isEqualTo("d-1");
        assertThat(run.getStartedAt()).isNotNull();
        assertThat(run.getCreatedAt()).isNotNull();
        assertThat(run.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("已存在的运行：updateById 而不是重复 insert（幂等收敛）")
    void updatesExistingRun() {
        WorkflowRun existing = new WorkflowRun();
        existing.setId(5L);
        existing.setStatus("in_progress");
        when(runMapper.selectOne(any())).thenReturn(existing);

        RunWriteService.UpsertOutcome outcome = service.upsert(
                data(123L, "in_progress", null, Instant.parse("2026-01-01T10:00:00Z"), null, null),
                "acme/app", "d-2");

        assertThat(outcome.created()).isFalse();
        verify(runMapper, never()).insert(any(WorkflowRun.class));
        verify(runMapper).updateById(existing);
        assertThat(existing.getId()).isEqualTo(5L);
        assertThat(existing.getDeliveryId()).isEqualTo("d-2");
    }

    @Test
    @DisplayName("状态缺失时兜底为 unknown，避免 NOT NULL 约束炸掉整条投递")
    void defaultsStatusToUnknown() {
        when(runMapper.selectOne(any())).thenReturn(null);

        service.upsert(data(1L, null, null, null, null, null), "acme/app", "d");

        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("status 与 conclusion 都缺省时 started_at 允许为空（不臆造时间）")
    void allowsMissingStartedAt() {
        when(runMapper.selectOne(any())).thenReturn(null);

        service.upsert(data(1L, "queued", null, null, null, null), "acme/app", "d");

        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runMapper).insert(captor.capture());
        assertThat(captor.getValue().getStartedAt()).isNull();
    }

    @Nested
    @DisplayName("完成与耗时")
    class Completion {

        @Test
        @DisplayName("首次完成：justFinished=true，并按 started/completed 算出耗时")
        void computesDurationAndFlagsJustFinished() {
            WorkflowRun existing = new WorkflowRun();
            existing.setId(5L);
            existing.setStatus("in_progress");
            when(runMapper.selectOne(any())).thenReturn(existing);

            RunWriteService.UpsertOutcome outcome = service.upsert(
                    data(123L, "completed", "success",
                            Instant.parse("2026-01-01T10:00:00Z"),
                            Instant.parse("2026-01-01T10:01:30Z"), null),
                    "acme/app", "d-3");

            assertThat(outcome.justFinished()).isTrue();
            assertThat(outcome.run().getDurationMs()).isEqualTo(90_000L);
            assertThat(outcome.run().getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("重复收到已完成事件：justFinished=false（不会重复告警）")
        void doesNotFlagJustFinishedTwice() {
            WorkflowRun existing = new WorkflowRun();
            existing.setId(5L);
            existing.setStatus("completed");
            existing.setConclusion("failure");
            when(runMapper.selectOne(any())).thenReturn(existing);

            RunWriteService.UpsertOutcome outcome = service.upsert(
                    data(123L, "completed", "failure",
                            Instant.parse("2026-01-01T10:00:00Z"),
                            Instant.parse("2026-01-01T10:05:00Z"), null),
                    "acme/app", "d-4");

            assertThat(outcome.justFinished()).isFalse();
        }

        @Test
        @DisplayName("completed_at 早于 started_at（时钟漂移）时耗时夹到 0，不出现负数")
        void clampsNegativeDuration() {
            when(runMapper.selectOne(any())).thenReturn(null);

            RunWriteService.UpsertOutcome outcome = service.upsert(
                    data(123L, "completed", "success",
                            Instant.parse("2026-01-01T10:05:00Z"),
                            Instant.parse("2026-01-01T10:00:00Z"), null),
                    "acme/app", "d-5");

            assertThat(outcome.run().getDurationMs()).isZero();
        }

        @Test
        @DisplayName("updated_at 缺失时用当前时间兜底，duration 仍为非负")
        void fallsBackToNowWhenUpdatedAtMissing() {
            when(runMapper.selectOne(any())).thenReturn(null);

            RunWriteService.UpsertOutcome outcome = service.upsert(
                    data(123L, "completed", "success",
                            Instant.parse("2026-01-01T10:00:00Z"), null, null),
                    "acme/app", "d-6");

            assertThat(outcome.run().getCompletedAt()).isNotNull();
            assertThat(outcome.run().getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("job / step 明细替换")
    class JobsReplacement {

        private GithubJob job(Long id, String name, List<String> needs, List<GithubStep> steps) {
            return new GithubJob(id, name, "completed", "success",
                    Instant.parse("2026-01-01T10:00:00Z"),
                    Instant.parse("2026-01-01T10:00:30Z"),
                    "ubuntu-latest", "https://github.com/job/" + id, needs, steps);
        }

        @Test
        @DisplayName("有旧明细时先删 step 再删 job，保证与 GitHub 完全一致")
        void replacesPreviousJobs() {
            when(runMapper.selectOne(any())).thenReturn(null);
            RunJob oldJob = new RunJob();
            oldJob.setId(11L);
            RunJob oldJob2 = new RunJob();
            oldJob2.setId(12L);
            when(jobMapper.selectList(any())).thenReturn(List.of(oldJob, oldJob2));

            service.upsert(data(1L, "completed", "success", null, null,
                    List.of(job(1L, "build", List.of(), List.of()))), "acme/app", "d");

            verify(stepMapper).delete(org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<RunStep>>any());
            verify(jobMapper).delete(org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<RunJob>>any());
            verify(jobMapper, times(1)).insert(any(RunJob.class));
        }

        @Test
        @DisplayName("needs=null 存 null（未知依赖），needs=[] 存空串（已知无依赖）")
        void distinguishesUnknownAndEmptyNeeds() {
            WorkflowRun run = new WorkflowRun();
            run.setId(1L);
            when(jobMapper.selectList(any())).thenReturn(List.of());

            service.replaceJobs(run, List.of(
                    job(1L, "unknown", null, null),
                    job(2L, "root", List.of(), null)));

            ArgumentCaptor<RunJob> captor = ArgumentCaptor.forClass(RunJob.class);
            verify(jobMapper, times(2)).insert(captor.capture());
            assertThat(captor.getAllValues().get(0).getNeeds()).isNull();
            assertThat(captor.getAllValues().get(1).getNeeds()).isEmpty();
        }

        @Test
        @DisplayName("needs 列表去空白并用逗号拼接")
        void joinsNeeds() {
            WorkflowRun run = new WorkflowRun();
            run.setId(1L);
            when(jobMapper.selectList(any())).thenReturn(List.of());

            service.replaceJobs(run, List.of(job(1L, "deploy", List.of(" lint ", "", "test (jdk21)"), null)));

            ArgumentCaptor<RunJob> captor = ArgumentCaptor.forClass(RunJob.class);
            verify(jobMapper).insert(captor.capture());
            assertThat(captor.getValue().getNeeds()).isEqualTo("lint,test (jdk21)");
        }

        @Test
        @DisplayName("needs 超长时截断到 1000 字符，避免超出列宽")
        void truncatesLongNeeds() {
            WorkflowRun run = new WorkflowRun();
            run.setId(1L);
            when(jobMapper.selectList(any())).thenReturn(List.of());
            String longName = "j".repeat(600);

            service.replaceJobs(run, List.of(job(1L, "x", List.of(longName, longName), null)));

            ArgumentCaptor<RunJob> captor = ArgumentCaptor.forClass(RunJob.class);
            verify(jobMapper).insert(captor.capture());
            assertThat(captor.getValue().getNeeds()).hasSize(1000);
        }

        @Test
        @DisplayName("job 耗时由 started/completed 计算，step 按 number 落库")
        void mapsJobsAndSteps() {
            WorkflowRun run = new WorkflowRun();
            run.setId(1L);
            when(jobMapper.selectList(any())).thenReturn(List.of());
            GithubStep step = new GithubStep("checkout", 1, "completed", "success",
                    Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-01-01T10:00:10Z"));

            service.replaceJobs(run, List.of(job(7L, "build", List.of(), List.of(step))));

            ArgumentCaptor<RunJob> jobCaptor = ArgumentCaptor.forClass(RunJob.class);
            verify(jobMapper).insert(jobCaptor.capture());
            assertThat(jobCaptor.getValue().getDurationMs()).isEqualTo(30_000L);
            assertThat(jobCaptor.getValue().getExternalJobId()).isEqualTo(7L);
            assertThat(jobCaptor.getValue().getRunnerName()).isEqualTo("ubuntu-latest");

            ArgumentCaptor<RunStep> stepCaptor = ArgumentCaptor.forClass(RunStep.class);
            verify(stepMapper).insert(stepCaptor.capture());
            assertThat(stepCaptor.getValue().getStepNumber()).isEqualTo(1);
            assertThat(stepCaptor.getValue().getName()).isEqualTo("checkout");
            assertThat(stepCaptor.getValue().getJobPk()).isNotNull();
        }

        @Test
        @DisplayName("job 未完成（缺 completed_at）时不算耗时，前端展示为进行中")
        void leavesDurationNullForUnfinishedJob() {
            WorkflowRun run = new WorkflowRun();
            run.setId(1L);
            when(jobMapper.selectList(any())).thenReturn(List.of());
            GithubJob running = new GithubJob(8L, "test", "in_progress", null,
                    Instant.parse("2026-01-01T10:00:00Z"), null, null, null, null, null);

            service.replaceJobs(run, List.of(running));

            ArgumentCaptor<RunJob> captor = ArgumentCaptor.forClass(RunJob.class);
            verify(jobMapper).insert(captor.capture());
            assertThat(captor.getValue().getDurationMs()).isNull();
        }
    }

    @Nested
    @DisplayName("commit 信息回填")
    class CommitEnrichment {

        @Test
        @DisplayName("缺少 sha 或没有任何可写字段时直接返回 0，不产生无意义的 UPDATE")
        void skipsUselessUpdate() {
            assertThat(service.enrichCommitInfo("acme/app", null, "msg", "author")).isZero();
            assertThat(service.enrichCommitInfo("acme/app", "sha", null, null)).isZero();

            verify(runMapper, never()).update(any(WorkflowRun.class), any());
        }

        @Test
        @DisplayName("只补 message 或只补 author 都允许")
        void patchesPartialInfo() {
            when(runMapper.update(any(WorkflowRun.class), any())).thenReturn(2);

            assertThat(service.enrichCommitInfo("acme/app", "sha", "fix: bug", null)).isEqualTo(2);

            ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
            verify(runMapper).update(captor.capture(), any());
            assertThat(captor.getValue().getCommitMessage()).isEqualTo("fix: bug");
            assertThat(captor.getValue().getCommitAuthor()).isNull();
            assertThat(captor.getValue().getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("超长 commit message 截断到 900 字符")
        void truncatesMessage() {
            when(runMapper.update(any(WorkflowRun.class), any())).thenReturn(1);

            service.enrichCommitInfo("acme/app", "sha", "m".repeat(2000), "Bob");

            ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
            verify(runMapper).update(captor.capture(), any());
            assertThat(captor.getValue().getCommitMessage()).hasSize(900);
        }

        @Test
        @DisplayName("为空字符串的 message 也会写库（用于清空错误信息场景不冲突）")
        void writesEmptyMessage() {
            when(runMapper.update(any(WorkflowRun.class), any())).thenReturn(1);

            assertThat(service.enrichCommitInfo("acme/app", "sha", "", null)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("upsert 里的 commit message 也做 900 字符截断")
    void truncatesCommitMessageOnUpsert() {
        when(runMapper.selectOne(any())).thenReturn(null);
        WorkflowRunData longMessage = new WorkflowRunData(
                1L, "CI", "main", "sha", 1, 1, "push", "completed", "success",
                Instant.now(), Instant.now(), Instant.now(), null, null, null,
                new GithubCommit("sha", "x".repeat(1500), Instant.now(), new GithubCommitAuthor("A", "a@x")),
                null);

        service.upsert(longMessage, "acme/app", "d");

        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runMapper).insert(captor.capture());
        assertThat(captor.getValue().getCommitMessage()).hasSize(900);
    }

    @Test
    @DisplayName("actor 优先取 triggering_actor，缺失时回落到 actor")
    void prefersTriggeringActor() {
        when(runMapper.selectOne(any())).thenReturn(null);
        WorkflowRunData d = new WorkflowRunData(1L, "CI", "main", "sha", 1, 1, "push",
                "queued", null, null, null, null, null,
                new GithubActor("plain-actor", "User"), null, null, null);

        service.upsert(d, "acme/app", "d");

        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runMapper).insert(captor.capture());
        assertThat(captor.getValue().getActor()).isEqualTo("plain-actor");
    }

    @Test
    @DisplayName("head_sha 缺失时用 head_commit.id 兜底")
    void fallsBackToCommitId() {
        when(runMapper.selectOne(any())).thenReturn(null);
        WorkflowRunData d = new WorkflowRunData(1L, "CI", "main", null, 1, 1, "push",
                "queued", null, null, null, null, null, null, null,
                new GithubCommit("commit-sha", "msg", null, null), null);

        service.upsert(d, "acme/app", "d");

        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runMapper).insert(captor.capture());
        assertThat(captor.getValue().getCommitSha()).isEqualTo("commit-sha");
    }

    @Test
    @DisplayName("payload 没有 jobs 时不动已有明细（避免把详情页刷空）")
    void leavesJobsUntouchedWhenPayloadHasNone() {
        when(runMapper.selectOne(any())).thenReturn(null);

        service.upsert(data(1L, "completed", "success", null, Instant.now(), null), "acme/app", "d");

        verify(jobMapper, never()).selectList(any());
        verify(jobMapper, never()).insert(any(RunJob.class));
        verify(jobMapper, never()).delete(any());
    }

    @Test
    @DisplayName("耗时计算不受时区影响：UTC 输入按 Asia/Shanghai 落库，差值为 90 秒")
    void mapsInstantsIntoConfiguredZone() {
        when(runMapper.selectOne(any())).thenReturn(null);

        RunWriteService.UpsertOutcome outcome = service.upsert(
                data(1L, "completed", "success",
                        Instant.parse("2026-01-01T02:00:00Z"),
                        Instant.parse("2026-01-01T02:01:30Z"), null),
                "acme/app", "d");

        assertThat(outcome.run().getStartedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 10, 0));
        assertThat(Duration.between(outcome.run().getStartedAt(), outcome.run().getCompletedAt()).toSeconds())
                .isEqualTo(90);
        assertThat(outcome.run().getStartedAt()).isNotEqualTo(
                LocalDateTime.ofInstant(Instant.parse("2026-01-01T02:00:00Z"), java.time.ZoneOffset.UTC));
    }
}
