package com.githublook.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.githublook.common.config.AppProperties;
import com.githublook.common.error.AppException;
import com.githublook.common.error.ErrorCode;
import com.githublook.repo.MonitoredRepo;
import com.githublook.repo.RepoService;
import com.githublook.webhook.dto.WebhookAck;
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

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WebhookIngestService · 入口快路径")
class WebhookIngestServiceTest {

    private static final String REPO = "acme/app";
    private static final String SECRET = "repo-secret";
    private static final String DELIVERY = "delivery-001";

    @Mock
    private RepoService repoService;
    @Mock
    private IdempotencyService idempotency;
    @Mock
    private DeliveryAuditService audit;
    @Mock
    private WebhookQueue queue;

    private final AppProperties props = new AppProperties();
    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebhookIngestService service;
    private MonitoredRepo enabledRepo;

    @BeforeEach
    void setUp() {
        service = new WebhookIngestService(props, verifier, repoService, idempotency, audit, queue, objectMapper);
        props.getWebhook().setDefaultSecret("global-secret");
        props.getWebhook().setAutoRegister(true);

        enabledRepo = new MonitoredRepo();
        enabledRepo.setId(1L);
        enabledRepo.setFullName(REPO);
        enabledRepo.setWebhookSecret(SECRET);
        enabledRepo.setEnabled(true);

        when(repoService.resolveSecret(REPO)).thenReturn(SECRET);
        when(repoService.findByFullName(REPO)).thenReturn(Optional.of(enabledRepo));
        when(idempotency.reserve(anyString())).thenReturn(true);
        when(audit.tryStart(anyString(), anyString(), anyString())).thenReturn(true);
    }

    private byte[] body(String event, String repo) {
        String json = """
                {"action":"%s","repository":{"full_name":"%s"}}
                """.formatted(event, repo);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("正常投递：验签通过 → 占位 → 入队 → 回 QUEUED")
    void queuesValidDelivery() {
        byte[] payload = body("completed", REPO);
        String sign = verifier.sign(payload, SECRET);

        WebhookAck ack = service.ingest("workflow_run", DELIVERY, sign, payload);

        assertThat(ack.status()).isEqualTo("QUEUED");
        assertThat(ack.deliveryId()).isEqualTo(DELIVERY);
        assertThat(ack.message()).contains("已受理");

        ArgumentCaptor<QueueMessage> captor = ArgumentCaptor.forClass(QueueMessage.class);
        verify(queue).enqueue(captor.capture());
        QueueMessage message = captor.getValue();
        assertThat(message.deliveryId()).isEqualTo(DELIVERY);
        assertThat(message.eventName()).isEqualTo("workflow_run");
        assertThat(message.repoFullName()).isEqualTo(REPO);
        assertThat(message.attempt()).isZero();
        assertThat(message.payload().getBytes(StandardCharsets.UTF_8)).isEqualTo(payload);

        verify(audit).tryStart(DELIVERY, "workflow_run", REPO);
        verify(audit).markQueued(DELIVERY);
    }

    @Nested
    @DisplayName("请求体与请求头校验")
    class RequestValidation {

        @Test
        @DisplayName("空请求体直接 400")
        void rejectsEmptyBody() {
            assertThatThrownBy(() -> service.ingest("workflow_run", DELIVERY, "sha256=00", new byte[0]))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }

        @Test
        @DisplayName("超过 max-payload-bytes 返回 413，且不进入后续流程")
        void rejectsOversizedBody() {
            props.getWebhook().setMaxPayloadBytes(1024);
            byte[] big = new byte[1025];

            assertThatThrownBy(() -> service.ingest("workflow_run", DELIVERY, "sha256=00", big))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getCode())
                    .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
            verify(queue, never()).enqueue(any());
        }

        @Test
        @DisplayName("缺少 X-GitHub-Delivery 无法做幂等，直接 400")
        void rejectsMissingDeliveryId() {
            byte[] payload = body("completed", REPO);

            assertThatThrownBy(() -> service.ingest("workflow_run", "  ", verifier.sign(payload, SECRET), payload))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("X-GitHub-Delivery");
        }

        @Test
        @DisplayName("请求体不是合法 JSON 返回 400")
        void rejectsMalformedJson() {
            byte[] payload = "{not-json".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> service.ingest("workflow_run", DELIVERY, "sha256=00", payload))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("JSON");
        }

        @Test
        @DisplayName("缺少 repository.full_name 返回 400（无法判断该用哪个密钥）")
        void rejectsMissingRepoName() {
            byte[] payload = "{\"action\":\"completed\"}".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> service.ingest("workflow_run", DELIVERY, "sha256=00", payload))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("repository.full_name");
        }
    }

    @Nested
    @DisplayName("仓库与密钥")
    class RepoResolution {

        @Test
        @DisplayName("仓库没有配置任何 secret → 404 REPO_NOT_CONFIGURED，而不是放行")
        void rejectsRepoWithoutSecret() {
            when(repoService.resolveSecret(REPO)).thenReturn("");
            byte[] payload = body("completed", REPO);

            assertThatThrownBy(() -> service.ingest("workflow_run", DELIVERY, "sha256=00", payload))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getCode())
                    .isEqualTo(ErrorCode.REPO_NOT_CONFIGURED);
        }

        @Test
        @DisplayName("签名不匹配 → 401 SIGNATURE_INVALID，且不入队、不占位")
        void rejectsInvalidSignature() {
            byte[] payload = body("completed", REPO);

            assertThatThrownBy(() -> service.ingest("workflow_run", DELIVERY,
                    verifier.sign(payload, "wrong-secret"), payload))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getCode())
                    .isEqualTo(ErrorCode.SIGNATURE_INVALID);

            verify(idempotency, never()).reserve(anyString());
            verify(queue, never()).enqueue(any());
        }

        @Test
        @DisplayName("未知仓库 + auto-register 关闭 → 404，不会偷偷建仓库")
        void rejectsUnknownRepoWhenAutoRegisterOff() {
            props.getWebhook().setAutoRegister(false);
            when(repoService.findByFullName("acme/unknown")).thenReturn(Optional.empty());
            when(repoService.resolveSecret("acme/unknown")).thenReturn("global-secret");
            byte[] payload = body("completed", "acme/unknown");

            assertThatThrownBy(() -> service.ingest("workflow_run", DELIVERY,
                    verifier.sign(payload, "global-secret"), payload))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getCode())
                    .isEqualTo(ErrorCode.REPO_NOT_CONFIGURED);

            verify(repoService, never()).registerIfAbsent(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("未知仓库 + auto-register 开启（验签用全局密钥）→ 自动注册后继续入队")
        void autoRegistersUnknownRepo() {
            MonitoredRepo created = new MonitoredRepo();
            created.setFullName("acme/unknown");
            created.setEnabled(true);
            when(repoService.resolveSecret("acme/unknown")).thenReturn("global-secret");
            when(repoService.findByFullName("acme/unknown")).thenReturn(Optional.empty());
            when(repoService.registerIfAbsent("acme", "unknown", null)).thenReturn(created);
            byte[] payload = body("completed", "acme/unknown");

            WebhookAck ack = service.ingest("workflow_run", DELIVERY,
                    verifier.sign(payload, "global-secret"), payload);

            assertThat(ack.status()).isEqualTo("QUEUED");
            verify(repoService).registerIfAbsent("acme", "unknown", null);
            verify(queue).enqueue(any());
        }

        @Test
        @DisplayName("仓库已停用 → IGNORED（记录审计，但不入队）")
        void ignoresDisabledRepo() {
            enabledRepo.setEnabled(false);
            byte[] payload = body("completed", REPO);

            WebhookAck ack = service.ingest("workflow_run", DELIVERY, verifier.sign(payload, SECRET), payload);

            assertThat(ack.status()).isEqualTo("IGNORED");
            assertThat(ack.message()).contains("停用");
            verify(audit).markIgnored(eq(DELIVERY), anyString());
            verify(queue, never()).enqueue(any());
        }
    }

    @Nested
    @DisplayName("幂等与特殊事件")
    class IdempotencyAndSpecialEvents {

        @Test
        @DisplayName("ping 事件回 PONG，用于 GitHub 侧联通性验证")
        void answersPing() {
            byte[] payload = body("ping", REPO);

            WebhookAck ack = service.ingest("ping", DELIVERY, verifier.sign(payload, SECRET), payload);

            assertThat(ack.status()).isEqualTo("PONG");
            verify(audit).markIgnored(eq(DELIVERY), anyString());
            verify(queue, never()).enqueue(any());
        }

        @Test
        @DisplayName("Redis 已存在该 delivery → DUPLICATE，不再重复落库")
        void deduplicatesInRedis() {
            when(idempotency.reserve(DELIVERY)).thenReturn(false);
            byte[] payload = body("completed", REPO);

            WebhookAck ack = service.ingest("workflow_run", DELIVERY, verifier.sign(payload, SECRET), payload);

            assertThat(ack.status()).isEqualTo("DUPLICATE");
            verify(audit, never()).tryStart(anyString(), anyString(), anyString());
            verify(queue, never()).enqueue(any());
        }

        @Test
        @DisplayName("Redis 丢了键但数据库已有记录 → 仍然 DUPLICATE（持久化兜底生效）")
        void deduplicatesInDatabase() {
            when(idempotency.reserve(DELIVERY)).thenReturn(true);
            when(audit.tryStart(DELIVERY, "workflow_run", REPO)).thenReturn(false);
            byte[] payload = body("completed", REPO);

            WebhookAck ack = service.ingest("workflow_run", DELIVERY, verifier.sign(payload, SECRET), payload);

            assertThat(ack.status()).isEqualTo("DUPLICATE");
            verify(queue, never()).enqueue(any());
        }

        @Test
        @DisplayName("入队失败 → 审计标记 FAILED 并把异常抛给调用方（GitHub 会重投）")
        void marksFailedWhenQueueIsUnavailable() {
            AppException boom = new AppException(ErrorCode.SERVICE_UNAVAILABLE, "队列不可用");
            org.mockito.Mockito.doThrow(boom).when(queue).enqueue(any());
            byte[] payload = body("completed", REPO);

            assertThatThrownBy(() -> service.ingest("workflow_run", DELIVERY,
                    verifier.sign(payload, SECRET), payload))
                    .isSameAs(boom);

            verify(audit).markFailed(DELIVERY, "队列不可用");
            verify(audit, never()).markQueued(anyString());
        }

        @Test
        @DisplayName("事件名缺失时记为 unknown，而不是抛 NPE")
        void defaultsUnknownEventName() {
            byte[] payload = body("completed", REPO);

            service.ingest(null, DELIVERY, verifier.sign(payload, SECRET), payload);

            ArgumentCaptor<QueueMessage> captor = ArgumentCaptor.forClass(QueueMessage.class);
            verify(queue).enqueue(captor.capture());
            assertThat(captor.getValue().eventName()).isEqualTo("unknown");
        }
    }
}
