package com.githublook.webhook;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.githublook.common.time.TimeMapper;
import com.githublook.webhook.mapper.WebhookDeliveryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Every delivery gets an audit row. The unique index on {@code delivery_id} is the durable
 * idempotency backstop, so insertion runs in its own transaction and a duplicate
 * is reported instead of poisoning the caller's transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryAuditService {

    private final WebhookDeliveryMapper deliveryMapper;
    private final TimeMapper time;

    /** @return false when the delivery id already exists */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryStart(String deliveryId, String eventName, String repoFullName) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setDeliveryId(deliveryId);
        delivery.setEventName(eventName);
        delivery.setRepoFullName(repoFullName);
        delivery.setStatus("RECEIVED");
        delivery.setReceivedAt(time.now());
        try {
            deliveryMapper.insert(delivery);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.info("duplicate_delivery deliveryId={}", deliveryId);
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markQueued(String deliveryId) {
        updateStatus(deliveryId, "QUEUED", null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessed(String deliveryId) {
        updateStatus(deliveryId, "PROCESSED", null);
        deliveryMapper.update(null, new LambdaUpdateWrapper<WebhookDelivery>()
                .eq(WebhookDelivery::getDeliveryId, deliveryId)
                .set(WebhookDelivery::getProcessedAt, time.now()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markIgnored(String deliveryId, String reason) {
        updateStatus(deliveryId, "IGNORED", truncate(reason));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String deliveryId, String error) {
        updateStatus(deliveryId, "FAILED", truncate(error));
        deliveryMapper.update(null, new LambdaUpdateWrapper<WebhookDelivery>()
                .eq(WebhookDelivery::getDeliveryId, deliveryId)
                .set(WebhookDelivery::getProcessedAt, time.now()));
    }

    public LocalDateTime lastReceivedAt() {
        WebhookDelivery latest = deliveryMapper.selectOne(new LambdaQueryWrapper<WebhookDelivery>()
                .orderByDesc(WebhookDelivery::getReceivedAt)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getReceivedAt();
    }

    public long countByStatus(String status) {
        Long count = deliveryMapper.selectCount(new LambdaQueryWrapper<WebhookDelivery>()
                .eq(WebhookDelivery::getStatus, status));
        return count == null ? 0 : count;
    }

    public long countSince(LocalDateTime from) {
        Long count = deliveryMapper.selectCount(new LambdaQueryWrapper<WebhookDelivery>()
                .ge(WebhookDelivery::getReceivedAt, from));
        return count == null ? 0 : count;
    }

    private void updateStatus(String deliveryId, String status, String error) {
        deliveryMapper.update(null, new LambdaUpdateWrapper<WebhookDelivery>()
                .eq(WebhookDelivery::getDeliveryId, deliveryId)
                .set(WebhookDelivery::getStatus, status)
                .set(WebhookDelivery::getErrorMessage, error));
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 900 ? value.substring(0, 900) : value;
    }
}
