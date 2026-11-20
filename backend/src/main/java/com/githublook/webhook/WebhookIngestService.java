package com.githublook.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.githublook.common.config.AppProperties;
import com.githublook.common.error.AppException;
import com.githublook.common.error.ErrorCode;
import com.githublook.repo.MonitoredRepo;
import com.githublook.repo.RepoService;
import com.githublook.webhook.dto.WebhookAck;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

/**
 * Fast path for inbound webhooks. The only work done on the request thread is:
 * verify the signature, claim the delivery id, push the raw body onto the Redis list.
 * Parsing and database writes happen on the consumer's virtual threads.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookIngestService {

    private final AppProperties props;
    private final WebhookSignatureVerifier verifier;
    private final RepoService repoService;
    private final IdempotencyService idempotency;
    private final DeliveryAuditService audit;
    private final WebhookQueue queue;
    private final ObjectMapper objectMapper;

    public WebhookAck ingest(String eventName, String deliveryId, String signature, byte[] body) {
        int maxBytes = props.getWebhook().getMaxPayloadBytes();
        if (body == null || body.length == 0) {
            throw AppException.badRequest("Webhook 请求体为空");
        }
        if (body.length > maxBytes) {
            throw new AppException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "请求体 %d 字节超过上限 %d 字节".formatted(body.length, maxBytes));
        }
        if (deliveryId == null || deliveryId.isBlank()) {
            throw AppException.badRequest("缺少 X-GitHub-Delivery 请求头");
        }
        String event = (eventName == null || eventName.isBlank()) ? "unknown" : eventName;
        String repoFullName = extractRepoFullName(body);

        String secret = repoService.resolveSecret(repoFullName);
        if (secret == null || secret.isBlank()) {
            throw new AppException(ErrorCode.REPO_NOT_CONFIGURED, "仓库 " + repoFullName + " 未配置 Secret");
        }
        if (!verifier.isValid(body, signature, secret)) {
            log.warn("signature_rejected repo={} deliveryId={} event={}", repoFullName, deliveryId, event);
            throw new AppException(ErrorCode.SIGNATURE_INVALID);
        }

        Optional<MonitoredRepo> repo = repoService.findByFullName(repoFullName);
        if (repo.isEmpty()) {
            if (!props.getWebhook().isAutoRegister()) {
                throw new AppException(ErrorCode.REPO_NOT_CONFIGURED, "仓库 " + repoFullName + " 未接入看板");
            }
            int slash = repoFullName.indexOf('/');
            repo = Optional.of(repoService.registerIfAbsent(
                    repoFullName.substring(0, slash), repoFullName.substring(slash + 1), null));
        }
        if (!Boolean.TRUE.equals(repo.get().getEnabled())) {
            audit.tryStart(deliveryId, event, repoFullName);
            audit.markIgnored(deliveryId, "仓库已在看板中停用");
            return new WebhookAck(deliveryId, "IGNORED", "仓库已停用，事件未处理");
        }

        if ("ping".equals(event)) {
            audit.tryStart(deliveryId, event, repoFullName);
            audit.markIgnored(deliveryId, "ping 事件仅用于联通性验证");
            log.info("webhook_ping repo={} deliveryId={}", repoFullName, deliveryId);
            return new WebhookAck(deliveryId, "PONG", "Webhook 联通成功");
        }

        if (!idempotency.reserve(deliveryId)) {
            log.info("delivery_deduplicated repo={} deliveryId={}", repoFullName, deliveryId);
            return new WebhookAck(deliveryId, "DUPLICATE", "重复投递，已忽略");
        }

        if (!audit.tryStart(deliveryId, event, repoFullName)) {
            // Redis lost the key but the durable row still exists.
            return new WebhookAck(deliveryId, "DUPLICATE", "重复投递，已忽略");
        }

        try {
            queue.enqueue(new QueueMessage(deliveryId, event, repoFullName,
                    new String(body, StandardCharsets.UTF_8), 0, Instant.now()));
        } catch (RuntimeException e) {
            audit.markFailed(deliveryId, e.getMessage());
            throw e;
        }
        audit.markQueued(deliveryId);
        log.info("webhook_accepted repo={} event={} deliveryId={} bytes={}",
                repoFullName, event, deliveryId, body.length);
        return new WebhookAck(deliveryId, "QUEUED", "已受理，正在异步处理");
    }

    private String extractRepoFullName(byte[] body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            throw AppException.badRequest("请求体不是合法的 JSON");
        }
        JsonNode repository = root.path("repository");
        String fullName = repository.path("full_name").asText(null);
        if (fullName == null || fullName.isBlank()) {
            throw AppException.badRequest("请求体缺少 repository.full_name，无法识别仓库");
        }
        return fullName;
    }
}
