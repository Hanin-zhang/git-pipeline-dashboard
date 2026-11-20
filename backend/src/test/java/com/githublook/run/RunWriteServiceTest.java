package com.githublook.run;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 {@link RunWriteService} 的写侧契约：
 * <ul>
 *   <li>upsert 以 {@code (repo_full_name, external_run_id)} 幂等收敛；</li>
 *   <li>晚到的「未完成」事件不得把已产出结论的运行拉回运行中（内存判断 + SQL 守卫双保险）；</li>
 *   <li>job 明细按 {@code external_job_id} 增量 upsert，只删除 GitHub 侧已消失的 job；</li>
 *   <li>needs 三态（null / 空串 / 逗号串）必须可区分。</li>
 * </ul>
 */
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

        // 默认：更新命中 1 行（真实库正常路径就是 1）
        when(runMapper.update(any(WorkflowRun.class), any())).thenReturn(1);
        doAnswer(inv -> {
            ((WorkflowRun) inv.getArgument(0)).setId(99L);
            return 1;
        }).when(runMapper).insert(any(WorkflowRun.class));

        // 新 job 的主键按自增模拟，保证后续 step 能拿到 jobPk
        AtomicLong jobIds = new AtomicLong(100);
        doAnswer(inv -> {
            ((RunJob) inv.getArgument(0)).setId(jobIds.incrementAndGet());
            return 1;
        }).when(jobMapper).insert(any(RunJob.class));

        when(jobMapper.selectList(any())).thenReturn(List.of());
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

    private WorkflowRunData running(Long id) {
        return data(id, "in_progress", null, Instant.parse("2026-01-01T10:00:00Z"), null, null);
    }

    private WorkflowRunData completed(Long id, String conclusion) {
        return data(id, "completed", conclusion,
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:01:30Z"), null);
    }

    private WorkflowRun existingRun(String status, String conclusion) {
        WorkflowRun run = new WorkflowRun();
        run.setId(5L);
        run.setExternalRunId(123L);
        run.setRepoFullName("acme/app");
        run.setStatus(status);
        run.setConclusion(conclusion);
        return run;
    }

    private GithubJob job(Long id, String name, List<String> needs, List<GithubStep> steps) {
        return new GithubJob(id, name, "completed", "success",
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:00:30Z"),
                "ubuntu-latest", "https://github.com/job/" + id, needs, steps);
    }

    private RunJob storedJob(Long id, Long externalJobId, String name) {
        RunJob stored = new RunJob();
        stored.setId(id);
        stored.setRunPk(1L);
        stored.setExternalJobId(externalJobId);
        stored.setName(name);
        return stored;
    }

    @Nested
    @DisplayName("新增与更新")
    class Upsert {

        @Test
        @DisplayName("新运行：字段完整映射，返回 created=true")
        void createsNewRun() {
            when(runMapper.selectOne(any())).thenReturn(null);

            RunWriteService.UpsertOutcome outcome = service.upsert(running(123L), "acme/app", "d-1");

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
        @DisplayName("已存在的运行：走带条件的 UPDATE（不是 updateById），并带 status != completed 守卫")
        void updatesExistingRunWithGuard() {
            when(runMapper.selectOne(any())).thenReturn(existingRun("in_progress", null));

            RunWriteService.UpsertOutcome outcome = service.upsert(running(123L), "acme/app", "d-2");

            assertThat(outcome.created()).isFalse();
            verify(runMapper, never()).insert(any(WorkflowRun.class));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Wrapper<WorkflowRun>> guard = ArgumentCaptor.forClass(Wrapper.class);
            ArgumentCaptor<WorkflowRun> patch = ArgumentCaptor.forClass(WorkflowRun.class);
            verify(runMapper).update(patch.capture(), guard.capture());

            assertThat(patch.getValue().getId()).isEqualTo(5L);
            assertThat(patch.getValue().getDeliveryId()).isEqualTo("d-2");

            // 非完成态写入必须把「不能覆盖已产出结论的行」下沉成 SQL 条件，防止并发下状态回退
            String sql = guard.getValue().getSqlSegment();
            assertThat(sql).contains("status").contains("conclusion");
            assertThat(((LambdaUpdateWrapper<WorkflowRun>) guard.getValue())
                    .getParamNameValuePairs().values()).contains("completed");
        }

        @Test
        @DisplayName("完成事件写入时不追加状态守卫（允许从运行中收敛到完成）")
        void finishedUpdateHasNoStatusGuard() {
            when(runMapper.selectOne(any())).thenReturn(existingRun("in_progress", null));

            service.upsert(completed(123L, "success"), "acme/app", "d-3");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Wrapper<WorkflowRun>> guard = ArgumentCaptor.forClass(Wrapper.class);
            verify(runMapper).update(any(WorkflowRun.class), guard.capture());
            assertThat(guard.getValue().getSqlSegment()).contains("id").doesNotContain("conclusion");
        }

        @Test
        @DisplayName("状态缺失时兜底为 unknown，避免违反 NOT NULL")
        void defaultsStatusToUnknown() {
            when(runMapper.selectOne(any())).thenReturn(null);

            service.upsert(data(1L, null, null, null, null, null), "acme/app", "d");

            ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
            verify(runMapper).insert(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo("unknown");
        }

        @Test
        @DisplayName("缺少 started_at 时允许为空，不臆造时间")
        void allowsMissingStartedAt() {
            when(runMapper.selectOne(any())).thenReturn(null);

            service.upsert(data(1L, "queued", null, null, null, null), "acme/app", "d");

            ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
            verify(runMapper).insert(captor.capture());
            assertThat(captor.getValue().getStartedAt()).isNull();
        }

        @Test
        @DisplayName("payload 没有 jobs 时不动已有明细（避免把详情页刷空）")
        void leavesJobsUntouchedWhenPayloadHasNone() {
            when(runMapper.selectOne(any())).thenReturn(null);

            service.upsert(completed(1L, "success"), "acme/app", "d");

            verify(jobMapper, never()).selectList(any());
            verify(jobMapper, never()).insert(any(RunJob.class));
            verify(jobMapper, never()).deleteById(any(Serializable.class));
        }
    }

    @Nested
    @DisplayName("乱序 / 并发保护")
    class OrderingGuards {

        @Test
        @DisplayName("迟到的 in_progress 事件不得把已完成的运行拉回运行中（不改库、原样返回）")
        void ignoresLateRunningEventOnFinishedRun() {
            WorkflowRun finished = existingRun("completed", "success");
            finished.setCompletedAt(LocalDateTime.of(2026, 1, 1, 10, 1));
            when(runMapper.selectOne(any())).thenReturn(finished);

            RunWriteService.UpsertOutcome outcome = service.upsert(running(123L), "acme/app", "late-1");

            assertThat(outcome.run().getStatus()).isEqualTo("completed");
            assertThat(outcome.created()).isFalse();
            assertThat(outcome.justFinished()).isFalse();
            verify(runMapper, never()).update(any(WorkflowRun.class), any());
        }

        @Test
        @DisplayName("已产出结论但 status 仍是 in_progress 的脏数据同样拦住（防止矛盾状态延续）")
        void ignoresLateRunningEventOnConcludedRow() {
            WorkflowRun dirty = existingRun("in_progress", "success");
            when(runMapper.selectOne(any())).thenReturn(dirty);

            RunWriteService.UpsertOutcome outcome = service.upsert(running(123L), "acme/app", "late-2");

            assertThat(outcome.run().getConclusion()).isEqualTo("success");
            verify(runMapper, never()).update(any(WorkflowRun.class), any());
        }

        @Test
        @DisplayName("SQL 守卫命中 0 行（并发被拒）：回读最新行并放弃本次写入，不误报 justFinished")
        void returnsFreshRowWhenAtomicGuardRejectsUpdate() {
            when(runMapper.selectOne(any())).thenReturn(existingRun("in_progress", null));
            when(runMapper.update(any(WorkflowRun.class), any())).thenReturn(0);
            WorkflowRun fresh = existingRun("completed", "success");
            when(runMapper.selectById(5L)).thenReturn(fresh);

            RunWriteService.UpsertOutcome outcome = service.upsert(running(123L), "acme/app", "race-1");

            assertThat(outcome.run()).isSameAs(fresh);
            assertThat(outcome.created()).isFalse();
            assertThat(outcome.justFinished()).isFalse();
        }

        @Test
        @DisplayName("守卫命中 0 行且回读为空时，退化为返回原对象（不抛异常）")
        void fallsBackToExistingWhenFreshRowMissing() {
            WorkflowRun existing = existingRun("in_progress", null);
            when(runMapper.selectOne(any())).thenReturn(existing);
            when(runMapper.update(any(WorkflowRun.class), any())).thenReturn(0);
            when(runMapper.selectById(5L)).thenReturn(null);

            assertThat(service.upsert(running(123L), "acme/app", "race-2").run()).isSameAs(existing);
        }
    }

    @Nested
    @DisplayName("完成与耗时")
    class Completion {

        @Test
        @DisplayName("首次完成：justFinished=true，耗时 = completed - started")
        void computesDurationAndFlagsJustFinished() {
            when(runMapper.selectOne(any())).thenReturn(existingRun("in_progress", null));

            RunWriteService.UpsertOutcome outcome = service.upsert(completed(123L, "success"), "acme/app", "d-3");

            assertThat(outcome.justFinished()).isTrue();
            assertThat(outcome.run().getDurationMs()).isEqualTo(90_000L);
            assertThat(outcome.run().getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("重复收到已完成事件：justFinished=false（不会重复告警）")
        void doesNotFlagJustFinishedTwice() {
            when(runMapper.selectOne(any())).thenReturn(existingRun("completed", "failure"));

            RunWriteService.UpsertOutcome outcome = service.upsert(completed(123L, "failure"), "acme/app", "d-4");

            assertThat(outcome.justFinished()).isFalse();
        }

        @Test
        @DisplayName("completed 早于 started（时钟漂移）时耗时夹到 0")
        void clampsNegativeDuration() {
            when(runMapper.selectOne(any())).thenReturn(null);
            WorkflowRunData skewed = data(123L, "completed", "success",
                    Instant.parse("2026-01-01T10:05:00Z"),
                    Instant.parse("2026-01-01T10:00:00Z"), null);

            assertThat(service.upsert(skewed, "acme/app", "d-5").run().getDurationMs()).isZero();
        }

        @Test
        @DisplayName("updated_at 缺失时用 now 兜底，耗时非负")
        void fallsBackToNowWhenUpdatedAtMissing() {
            when(runMapper.selectOne(any())).thenReturn(null);
            WorkflowRunData noUpdated = data(123L, "completed", "success",
                    Instant.parse("2026-01-01T10:00:00Z"), null, null);

            RunWriteService.UpsertOutcome outcome = service.upsert(noUpdated, "acme/app", "d-6");

            assertThat(outcome.run().getCompletedAt()).isNotNull();
            assertThat(outcome.run().getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("耗时按 Asia/Shanghai 落库，UTC 输入差值不变")
        void mapsInstantsIntoConfiguredZone() {
            when(runMapper.selectOne(any())).thenReturn(null);
            WorkflowRunData utc = data(1L, "completed", "success",
                    Instant.parse("2026-01-01T02:00:00Z"),
                    Instant.parse("2026-01-01T02:01:30Z"), null);

            RunWriteService.UpsertOutcome outcome = service.upsert(utc, "acme/app", "d");

            assertThat(outcome.run().getStartedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 10, 0));
            assertThat(Duration.between(outcome.run().getStartedAt(), outcome.run().getCompletedAt()).toSeconds())
                    .isEqualTo(90);
            assertThat(outcome.run().getStartedAt())
                    .isNotEqualTo(LocalDateTime.ofInstant(Instant.parse("2026-01-01T02:00:00Z"), ZoneOffset.UTC));
        }
    }

    @Nested
    @DisplayName("job / step 增量同步")
    class JobsSync {

        @Test
        @DisplayName("已存在的 job 按 external_job_id 更新，不删不重插（主键稳定）")
        void updatesExistingJobById() {
            WorkflowRun run = existingRun("completed", "success");
            run.setId(1L);
            when(jobMapper.selectList(any())).thenReturn(List.of(storedJob(11L, 1L, "build")));

            service.replaceJobs(run, List.of(job(1L, "build-v2", List.of("lint"), null)));

            ArgumentCaptor<RunJob> captor = ArgumentCaptor.forClass(RunJob.class);
            verify(jobMapper).updateById(captor.capture());
            assertThat(captor.getValue().getId()).isEqualTo(11L);
            assertThat(captor.getValue().getName()).isEqualTo("build-v2");
            assertThat(captor.getValue().getNeeds()).isEqualTo("lint");
            verify(jobMapper, never()).insert(any(RunJob.class));
            verify(jobMapper, never()).deleteById(any(Serializable.class));
        }

        @Test
        @DisplayName("新 job 直接插入")
        void insertsNewJobs() {
            WorkflowRun run = existingRun("completed", "success");
            run.setId(1L);

            service.replaceJobs(run, List.of(job(1L, "build", List.of(), null)));

            ArgumentCaptor<RunJob> captor = ArgumentCaptor.forClass(RunJob.class);
            verify(jobMapper).insert(captor.capture());
            assertThat(captor.getValue().getExternalJobId()).isEqualTo(1L);
            assertThat(captor.getValue().getRunPk()).isEqualTo(1L);
        }

        @Test
        @DisplayName("GitHub 侧已消失的 job 才删除（连同其 step）")
        void removesOnlyStaleJobs() {
            WorkflowRun run = existingRun("completed", "success");
            run.setId(1L);
            when(jobMapper.selectList(any())).thenReturn(List.of(
                    storedJob(11L, 1L, "build"),
                    storedJob(12L, 2L, "removed-job")));

            service.replaceJobs(run, List.of(job(1L, "build", List.of(), List.of())));

            verify(jobMapper).updateById(any(RunJob.class));
            verify(jobMapper).deleteById(12L);
            verify(jobMapper, never()).deleteById(11L);
            // 一次给保留的 job 重刷 step，一次清理被删 job 的 step
            verify(stepMapper, times(2)).delete(any());
        }

        @Test
        @DisplayName("step 每次按 job 整体替换：先删后插")
        void replacesStepsPerJob() {
            WorkflowRun run = existingRun("completed", "success");
            run.setId(1L);
            GithubStep step = new GithubStep("checkout", 1, "completed", "success",
                    Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-01-01T10:00:10Z"));

            service.replaceJobs(run, List.of(job(1L, "build", List.of(), List.of(step))));

            verify(stepMapper).delete(any());
            ArgumentCaptor<RunStep> captor = ArgumentCaptor.forClass(RunStep.class);
            verify(stepMapper).insert(captor.capture());
            assertThat(captor.getValue().getStepNumber()).isEqualTo(1);
            assertThat(captor.getValue().getName()).isEqualTo("checkout");
            assertThat(captor.getValue().getJobPk()).isNotNull();
        }

        @Test
        @DisplayName("steps 为 null 时只清空不插入（GitHub 尚未回填步骤）")
        void clearsStepsWhenPayloadHasNone() {
            WorkflowRun run = existingRun("completed", "success");
            run.setId(1L);

            service.replaceJobs(run, List.of(job(1L, "build", List.of(), null)));

            verify(stepMapper).delete(any());
            verify(stepMapper, never()).insert(any(RunStep.class));
        }

        @Test
        @DisplayName("payload 里重复的 job id 只处理一次；缺 id 的 job 直接跳过")
        void ignoresDuplicateAndNullJobIds() {
            WorkflowRun run = existingRun("completed", "success");
            run.setId(1L);

            service.replaceJobs(run, List.of(
                    job(1L, "build", List.of(), null),
                    job(1L, "build-duplicate", List.of(), null),
                    job(null, "no-id", List.of(), null)));

            ArgumentCaptor<RunJob> captor = ArgumentCaptor.forClass(RunJob.class);
            verify(jobMapper, times(1)).insert(captor.capture());
            assertThat(captor.getValue().getName()).isEqualTo("build");
        }

        @Test
        @DisplayName("needs 三态：null=未知，空数组=根节点，列表=依赖名")
        void distinguishesUnknownAndEmptyNeeds() {
            WorkflowRun run = existingRun("completed", "success");
            run.setId(1L);

            service.replaceJobs(run, List.of(
                    job(1L, "unknown", null, null),
                    job(2L, "root", List.of(), null),
                    job(3L, "dependent", List.of("lint", "test (jdk21)"), null)));

            ArgumentCaptor<RunJob> captor = ArgumentCaptor.forClass(RunJob.class);
            verify(jobMapper, times(3)).insert(captor.capture());
            assertThat(captor.getAllValues().get(0).getNeeds()).isNull();
            assertThat(captor.getAllValues().get(1).getNeeds()).isEmpty();
            assertThat(captor.getAllValues().get(2).getNeeds()).isEqualTo("lint,test (jdk21)");
        }

        @Test
        @DisplayName("needs 去空白、去空项后逗号拼接")
        void joinsNeeds() {
            WorkflowRun run = existingRun("completed", "success");
            run.setId(1L);

            service.replaceJobs(run, List.of(job(1L, "deploy", List.of(" lint ", "", "test (jdk21)"), null)));

            ArgumentCaptor<RunJob> captor = ArgumentCaptor.forClass(RunJob.class);
            verify(jobMapper).insert(captor.capture());
            assertThat(captor.getValue().getNeeds()).isEqualTo("lint,test (jdk21)");
        }

        @Test
        @DisplayName("needs 超长截断到 1000 字符")
        void truncatesLongNeeds() {
            WorkflowRun run = existingRun("completed", "success");
            run.setId(1L);
            String longName = "j".repeat(600);

            service.replaceJobs(run, List.of(job(1L, "x", List.of(longName, longName), null)));

            ArgumentCaptor<RunJob> captor = ArgumentCaptor.forClass(RunJob.class);
            verify(jobMapper).insert(captor.capture());
            assertThat(captor.getValue().getNeeds()).hasSize(1000);
        }

        @Test
        @DisplayName("job 耗时由 started/completed 计算；未完成时留空")
        void computesJobDurationOnlyWhenFinished() {
            WorkflowRun run = existingRun("completed", "success");
            run.setId(1L);
            GithubJob running = new GithubJob(8L, "test", "in_progress", null,
                    Instant.parse("2026-01-01T10:00:00Z"), null, null, null, null, null);

            service.replaceJobs(run, List.of(job(7L, "build", List.of(), null), running));

            ArgumentCaptor<RunJob> captor = ArgumentCaptor.forClass(RunJob.class);
            verify(jobMapper, times(2)).insert(captor.capture());
            assertThat(captor.getAllValues().get(0).getDurationMs()).isEqualTo(30_000L);
            assertThat(captor.getAllValues().get(1).getDurationMs()).isNull();
        }

        @Test
        @DisplayName("没有 external_job_id 的历史行无法匹配，按新 job 插入（不会被误更新）")
        void legacyRowsWithoutExternalIdAreNotMatched() {
            WorkflowRun run = existingRun("completed", "success");
            run.setId(1L);
            when(jobMapper.selectList(any())).thenReturn(List.of(storedJob(11L, null, "legacy")));

            service.replaceJobs(run, List.of(job(1L, "build", List.of(), null)));

            verify(jobMapper).insert(any(RunJob.class));
            verify(jobMapper, never()).updateById(any(RunJob.class));
        }
    }

    @Nested
    @DisplayName("commit 信息回填")
    class CommitEnrichment {

        @Test
        @DisplayName("缺少 sha 或没有任何可写字段时直接返回 0")
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
        @DisplayName("空字符串 message 也写库（用于清空错误信息场景不冲突）")
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
                "queued", null, null, null, null, null,
                new GithubActor("a", "User"), null,
                new GithubCommit("commit-sha", "msg", null, null), null);

        service.upsert(d, "acme/app", "d");

        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runMapper).insert(captor.capture());
        assertThat(captor.getValue().getCommitSha()).isEqualTo("commit-sha");
    }
}
