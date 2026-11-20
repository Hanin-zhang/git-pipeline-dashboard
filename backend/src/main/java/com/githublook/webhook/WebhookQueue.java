package com.githublook.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.githublook.common.config.AppProperties;
import com.githublook.common.error.AppException;
import com.githublook.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis List used as the webhook buffer: the request thread only pushes and returns,
 * the parsing/persistence work happens on a separate virtual thread.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookQueue {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AppProperties props;

    public void enqueue(QueueMessage message) {
        try {
            redis.opsForList().leftPush(props.getQueue().getKey(), serialize(message));
        } catch (RedisConnectionFailureException e) {
            log.error("queue_unavailable deliveryId={}", message.deliveryId(), e);
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE, "事件队列暂时不可用，请稍后重试", e);
        }
    }

    /** Non-blocking pop; empty Optional means the queue is idle. */
    public Optional<QueueMessage> poll() {
        String raw;
        try {
            raw = redis.opsForList().rightPop(props.getQueue().getKey());
        } catch (RedisConnectionFailureException e) {
            log.error("queue_poll_failed", e);
            return Optional.empty();
        }
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, QueueMessage.class));
        } catch (JsonProcessingException e) {
            log.error("queue_message_corrupted raw={}", raw, e);
            pushRaw(props.getQueue().getDeadLetterKey(), raw);
            return Optional.empty();
        }
    }

    public void deadLetter(QueueMessage message, String error) {
        try {
            redis.opsForHash().put(props.getQueue().getDeadLetterKey(), message.deliveryId(),
                    error + " | " + message.eventName() + " | " + message.enqueuedAt());
            redis.expire(props.getQueue().getDeadLetterKey(), Duration.ofDays(7));
        } catch (RedisConnectionFailureException e) {
            log.error("dead_letter_write_failed deliveryId={}", message.deliveryId(), e);
        }
    }

    public long size() {
        try {
            Long size = redis.opsForList().size(props.getQueue().getKey());
            return size == null ? 0 : size;
        } catch (RedisConnectionFailureException e) {
            return -1;
        }
    }

    private void pushRaw(String key, String raw) {
        try {
            redis.opsForHash().put(key, "corrupted-" + System.currentTimeMillis(), raw);
        } catch (RuntimeException ignored) {
            // best effort only
        }
    }

    private String serialize(QueueMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise queue message", e);
        }
    }
}
