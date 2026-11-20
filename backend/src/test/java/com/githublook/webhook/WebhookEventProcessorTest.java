package com.githublook.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.githublook.github.GithubJobSyncService;
import com.githublook.run.RunWriteService;
import com.githublook.run.WorkflowRun;
import com.githublook.settings.AlertNotifier;
import com.githublook.stats.StatsCache;
import com.githublook.stream.RunEventPayload;
import com.githublook.stream.SseHub;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WebhookEventProcessor · 消费者业务逻辑")
class WebhookEventProcessorTest {

    @Mock
    private RunWriteService runWriteService;
    @Mock
    private DeliveryAuditService audit;
    @Mock
    private SseHub sseHub;
    @Mock
    private StatsCache statsCache;
    @Mock
    private AlertNotifier alertNotifier;
    @Mock
    private GithubJobSyncService jobSyncService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private WebhookEventProcessor processor;

    private static final String RUN_JSON = """
            {"action":"completed",
             "repository":{"full_name":"acme/app"},
             "workflow_run":{
               "id":12345,"name":"CI","head_branch":"main","head_sha":"abcdef1234567",
               "run_number":42,"event":"push","status":"completed","conclusion":"success",
               "run_started_at":"2026-01-01T10:00:00Z","created_at":"2026-01-01T10:00:00Z",
               "updated_at":"2026-01-01T10:01:30Z","html_url":"https://github.com/acme/app/actions/runs/12345",
               "actor":{"login":"alice"},
               "head_commit":{"id":"abcdef1234567","message":"feat: add cache",
                              "author":{"name":"Alice","email":"a@x.io"}},
               "jobs":[{"id":1,"name":"build","status":"completed","conclusion":"success",
                        "started_at":"2026-01-01T10:00:05Z","completed_at":"2026-01-01T10:01:00Z",
                        "needs":[],"steps":[]}]}}
            """;

    @BeforeEach
    void setUp() {
        processor = new WebhookEventProcessor(objectMapper, runWriteService, audit,
                sseHub, statsCache, alertNotifier, jobSyncService);
    }

    private QueueMessage message(String event, String json) {
        return new QueueMessage("d-1", event, "acme/app", json, 0, Instant.now());
    }

    private WorkflowRun run(String status, String conclusion) {
        WorkflowRun run = new WorkflowRun();
        run.setId(7L);
        run.setExternalRunId(12345L);
        run.setRepoFullName("acme/app");
        run.setWorkflowName("CI");
        run.setBranch("main");
        run.setStatus(status);
        run.setConclusion(conclusion);
        run.setActor("alice");
        run.setDurationMs(90000L);
        return run;
    }

    private RunWriteService.UpsertOutcome outcome(boolean created, boolean justFinished, String status, String conclusion) {
        return new RunWriteService.UpsertOutcome(run(status, conclusion), created, justFinished);
    }

    @Nested
    @DisplayName("workflow_run 事件")
    class WorkflowRunHandling {

        @Test
        @DisplayName("首次出现的运行 → 广播 run.started，并清理统计缓存")
        void broadcastsStartedForNewRun() {
            when(runWriteService.upsert(any(), eq("acme/app"), eq("d-1")))
                    .thenReturn(outcome(true, false, "in_progress", null));

            processor.process(message("workflow_run", RUN_JSON));

            verify(statsCache).invalidate();
            verify(audit).markProcessed("d-1");

            ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
            verify(sseHub).broadcast(eq("run.started"), payload.capture());
            RunEventPayload event = (RunEventPayload) payload.getValue();
            assertThat(event.runId()).isEqualTo(7L);
            assertThat(event.type()).isEqualTo("running");
            assertThat(event.repoFullName()).isEqualTo("acme/app");
            assertThat(event.durationMs()).isEqualTo(90000L);
        }

        @Test
        @DisplayName("未结束的重复推送 → run.updated")
        void broadcastsUpdatedForUnfinishedRun() {
            when(runWriteService.upsert(any(), anyString(), anyString()))
                    .thenReturn(outcome(false, false, "in_progress", null));

            processor.process(message("workflow_run", RUN_JSON));

            verify(sseHub).broadcast(eq("run.updated"), any());
            verify(alertNotifier, never()).runFailed(any());
        }

        @Test
        @DisplayName("刚刚成功结束 → run.completed，不发告警")
        void broadcastsCompleted() {
            when(runWriteService.upsert(any(), anyString(), anyString()))
                    .thenReturn(outcome(false, true, "completed", "success"));

            processor.process(message("workflow_run", RUN_JSON));

            verify(sseHub).broadcast(eq("run.completed"), any());
            verify(alertNotifier, never()).runFailed(any());
        }

        @Test
        @DisplayName("刚刚失败结束 → run.failed 且触发告警通知")
        void broadcastsFailedAndNotifies() {
            RunWriteService.UpsertOutcome failure = outcome(false, true, "completed", "failure");
            when(runWriteService.upsert(any(), anyString(), anyString())).thenReturn(failure);

            processor.process(message("workflow_run", RUN_JSON));

            verify(sseHub).broadcast(eq("run.failed"), any());
            verify(alertNotifier).runFailed(failure.run());
        }

        @Test
        @DisplayName("已结束的运行再次收到事件（GitHub 重投）→ 不重复告警")
        void doesNotNotifyTwiceForSameFailure() {
            when(runWriteService.upsert(any(), anyString(), anyString()))
                    .thenReturn(outcome(false, false, "completed", "failure"));

            processor.process(message("workflow_run", RUN_JSON));

            verify(sseHub).broadcast(eq("run.updated"), any());
            verify(alertNotifier, never()).runFailed(any());
        }

        @Test
        @DisplayName("payload 自带 jobs → 不再调用 GitHub API 补拉")
        void trustsPayloadJobs() {
            when(runWriteService.upsert(any(), anyString(), anyString()))
                    .thenReturn(outcome(true, false, "completed", "success"));

            processor.process(message("workflow_run", RUN_JSON));

            verify(jobSyncService).fillIfMissing(any(), eq(true));
        }

        @Test
        @DisplayName("缺少 run.id → PermanentProcessingException（重试也没用，直接死信）")
        void rejectsPayloadWithoutRunId() {
            String json = "{\"workflow_run\":{\"name\":\"CI\"}}";

            assertThatThrownBy(() -> processor.process(message("workflow_run", json)))
                    .isInstanceOf(PermanentProcessingException.class)
                    .hasMessageContaining("run.id");
            verify(runWriteService, never()).upsert(any(), anyString(), anyString());
        }

        @Test
        @DisplayName("payload 无法解析 → PermanentProcessingException")
        void rejectsUnparseablePayload() {
            assertThatThrownBy(() -> processor.process(message("workflow_run", "{{{bad")))
                    .isInstanceOf(PermanentProcessingException.class)
                    .hasMessageContaining("无法解析");
        }

        @Test
        @DisplayName("payload 里的仓库与入队时的仓库不一致：以入队记录为准并继续处理")
        void prefersIngestRepoOverPayloadRepo() {
            String json = RUN_JSON.replace("\"acme/app\"", "\"acme/other\"");
            when(runWriteService.upsert(any(), anyString(), anyString()))
                    .thenReturn(outcome(true, false, "in_progress", null));

            processor.process(message("workflow_run", json));

            verify(runWriteService).upsert(any(), eq("acme/app"), eq("d-1"));
        }
    }

    @Nested
    @DisplayName("push 事件")
    class PushHandling {

        private final String pushJson = """
                {"ref":"refs/heads/feature/x","before":"aaa","after":"bbb",
                 "repository":{"full_name":"acme/app"},
                 "pusher":{"login":"bob"},
                 "head_commit":{"id":"bbb","message":"fix: null pointer",
                                "author":{"name":"Bob","email":"b@x.io"}}}
                """;

        @Test
        @DisplayName("把 commit 信息回填到同一 sha 的运行上")
        void enrichesRunsWithCommitInfo() {
            when(runWriteService.enrichCommitInfo("acme/app", "bbb", "fix: null pointer", "Bob"))
                    .thenReturn(2);

            processor.process(message("push", pushJson));

            verify(runWriteService).enrichCommitInfo("acme/app", "bbb", "fix: null pointer", "Bob");
            verify(audit).markProcessed("d-1");
            verify(sseHub, never()).broadcast(anyString(), any());
        }

        @Test
        @DisplayName("push 事件不解析 commit 时不阻塞（缺 head_commit 也能处理）")
        void toleratesMissingHeadCommit() {
            String json = """
                    {"ref":"refs/heads/main","after":"ccc","repository":{"full_name":"acme/app"}}
                    """;

            processor.process(message("push", json));

            verify(runWriteService).enrichCommitInfo("acme/app", "ccc", null, null);
        }

        @Test
        @DisplayName("缺少 repository.full_name → PermanentProcessingException")
        void rejectsPushWithoutRepo() {
            assertThatThrownBy(() -> processor.process(message("push", "{\"after\":\"x\"}")))
                    .isInstanceOf(PermanentProcessingException.class)
                    .hasMessageContaining("repository.full_name");
        }
    }

    @Nested
    @DisplayName("其它事件")
    class OtherEvents {

        @Test
        @DisplayName("workflow_job 记 IGNORED，不写数据库（job 明细已在 workflow_run 里）")
        void ignoresWorkflowJob() {
            processor.process(message("workflow_job", "{}"));

            verify(audit).markIgnored("d-1", "workflow_job 事件暂不单独落库");
            verify(runWriteService, never()).upsert(any(), anyString(), anyString());
        }

        @Test
        @DisplayName("未订阅的事件类型记 IGNORED，不抛异常（GitHub 会投递很多无关事件）")
        void ignoresUnknownEvent() {
            processor.process(message("issues", "{}"));

            verify(audit).markIgnored(eq("d-1"), org.mockito.ArgumentMatchers.contains("未订阅"));
        }

        @Test
        @DisplayName("ping 重复进入消费者也安全")
        void ignoresPing() {
            processor.process(message("ping", "{}"));

            verify(audit).markIgnored("d-1", "ping 事件仅用于联通性验证");
        }
    }

    @Test
    @DisplayName("补拉 job 依赖 payload 是否携带 jobs 判断，不会盲目请求 API")
    void passesWhetherPayloadHadJobs() {
        String json = """
                {"workflow_run":{"id":12345,"name":"CI","status":"completed","conclusion":"success"}}
                """;
        when(runWriteService.upsert(any(), anyString(), anyString()))
                .thenReturn(outcome(true, false, "completed", "success"));

        processor.process(message("workflow_run", json));

        verify(jobSyncService).fillIfMissing(any(), eq(false));
        verify(jobSyncService, never()).fillIfMissing(any(), eq(true));
    }

    @Test
    @DisplayName("广播 payload 的 type 与状态一致（前端靠它上色）")
    void mapsTypeFromState() {
        when(runWriteService.upsert(any(), anyString(), anyString()))
                .thenReturn(outcome(false, true, "completed", "timed_out"));

        processor.process(message("workflow_run", RUN_JSON));

        ArgumentCaptor<Object> captured = ArgumentCaptor.forClass(Object.class);
        verify(sseHub).broadcast(eq("run.failed"), captured.capture());
        assertThat(((RunEventPayload) captured.getValue()).type()).isEqualTo("failed");
    }

    @Test
    @DisplayName("payload 含 jobs 列表时不会带 false 去触发 API 补拉")
    void doesNotCallApiWhenJobsPresent() {
        when(runWriteService.upsert(any(), anyString(), anyString()))
                .thenReturn(outcome(true, false, "in_progress", null));

        processor.process(message("workflow_run", RUN_JSON));

        verify(jobSyncService, never()).fillIfMissing(any(), eq(false));
    }

    @Test
    @DisplayName("SSE 广播只发生一次，避免前端重复插入同一条事件")
    void broadcastsExactlyOnce() {
        when(runWriteService.upsert(any(), anyString(), anyString()))
                .thenReturn(outcome(true, false, "in_progress", null));

        processor.process(message("workflow_run", RUN_JSON));

        verify(sseHub, org.mockito.Mockito.times(1)).broadcast(eq("run.started"), any());
    }

    @Test
    @DisplayName("parse 结果里 jobs 为 null 时 fillIfMissing 收到 false")
    void handlesNullJobsList() {
        String json = RUN_JSON.replace("\"jobs\":[", "\"jobs_ignored\":[");
        when(runWriteService.upsert(any(), anyString(), anyString()))
                .thenReturn(outcome(true, false, "completed", "success"));

        processor.process(message("workflow_run", json));

        verify(jobSyncService).fillIfMissing(any(), eq(false));
    }

    @Test
    @DisplayName("事件处理顺序：先落库，再刷缓存，再广播")
    void keepsOrderOfSideEffects() {
        when(runWriteService.upsert(any(), anyString(), anyString()))
                .thenReturn(outcome(true, false, "in_progress", null));

        processor.process(message("workflow_run", RUN_JSON));

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(runWriteService, statsCache, sseHub);
        inOrder.verify(runWriteService).upsert(any(), anyString(), anyString());
        inOrder.verify(statsCache).invalidate();
        inOrder.verify(sseHub).broadcast(anyString(), any());
    }

    @Test
    @DisplayName("List 类型的 job 字段缺失时不会 NPE")
    void toleratesMissingJobsField() {
        String json = """
                {"workflow_run":{"id":9,"name":"CI","status":"queued","conclusion":null}}
                """;
        when(runWriteService.upsert(any(), anyString(), anyString()))
                .thenReturn(outcome(true, false, "queued", null));

        processor.process(message("workflow_run", json));

        verify(jobSyncService).fillIfMissing(any(), eq(false));
    }

    @Test
    @DisplayName("payload 的 List<GithubJob> 会被正确传递（用于判断是否需要补拉）")
    void detectsNonEmptyJobs() {
        assertThat(RUN_JSON).contains("\"jobs\":[");
        when(runWriteService.upsert(any(), anyString(), anyString()))
                .thenReturn(outcome(true, false, "completed", "success"));

        processor.process(message("workflow_run", RUN_JSON));

        ArgumentCaptor<Boolean> hasJobs = ArgumentCaptor.forClass(Boolean.class);
        verify(jobSyncService).fillIfMissing(any(), hasJobs.capture());
        assertThat(hasJobs.getValue()).isTrue();
    }
}
