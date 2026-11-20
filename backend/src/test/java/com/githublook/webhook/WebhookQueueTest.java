package com.githublook.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.githublook.common.config.AppProperties;
import com.githublook.common.error.AppException;
import com.githublook.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WebhookQueue · Redis List 缓冲")
class WebhookQueueTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ListOperations<String, String> listOps;
    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private final AppProperties props = new AppProperties();
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private WebhookQueue queue;

    private final QueueMessage message = new QueueMessage("d-1", "workflow_run", "acme/app",
            "{\"a\":1}", 0, Instant.parse("2026-01-01T00:00:00Z"));

    @BeforeEach
    void setUp() {
        queue = new WebhookQueue(redis, objectMapper, props);
        when(redis.opsForList()).thenReturn(listOps);
        when(redis.opsForHash()).thenReturn(hashOps);
    }

    @Test
    @DisplayName("enqueue 使用 leftPush，消息以 JSON 形式入队（原始 payload 原样保留）")
    void enqueuesJsonOntoConfiguredKey() {
        queue.enqueue(message);

        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(listOps).leftPush(eq(props.getQueue().getKey()), raw.capture());
        assertThat(raw.getValue()).contains("\"deliveryId\":\"d-1\"")
                .contains("\"eventName\":\"workflow_run\"")
                .contains("\"repoFullName\":\"acme/app\"");
    }

    @Test
    @DisplayName("enqueue 时 Redis 不可用 → 503，让 GitHub 重投而不是静默丢事件")
    void enqueueFailsWithServiceUnavailable() {
        when(listOps.leftPush(anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> queue.enqueue(message))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("poll 用 rightPop，保证 FIFO（先进先出）")
    void pollsFromTheTail() throws Exception {
        when(listOps.rightPop(props.getQueue().getKey()))
                .thenReturn(objectMapper.writeValueAsString(message));

        Optional<QueueMessage> polled = queue.poll();

        assertThat(polled).isPresent();
        assertThat(polled.get().deliveryId()).isEqualTo("d-1");
        assertThat(polled.get().payload()).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("队列为空返回 empty，消费者据此 park 而不是忙等")
    void returnsEmptyWhenQueueIsIdle() {
        when(listOps.rightPop(anyString())).thenReturn(null);

        assertThat(queue.poll()).isEmpty();
    }

    @Test
    @DisplayName("poll 遇到 Redis 异常时返回 empty，循环不中断")
    void pollSurvivesRedisFailure() {
        when(listOps.rightPop(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(queue.poll()).isEmpty();
    }

    @Test
    @DisplayName("消息体损坏 → 落死信区（不阻塞队列），并返回 empty")
    void corruptedMessageGoesToDeadLetterArea() {
        when(listOps.rightPop(anyString())).thenReturn("{not-valid-json");

        assertThat(queue.poll()).isEmpty();

        verify(hashOps).put(eq(props.getQueue().getDeadLetterKey()),
                org.mockito.ArgumentMatchers.startsWith("corrupted-"), eq("{not-valid-json"));
    }

    @Test
    @DisplayName("deadLetter 写入 Hash 并设置 7 天过期，避免死信无限堆积")
    void deadLetterWritesHashWithTtl() {
        queue.deadLetter(message, "boom");

        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(hashOps).put(eq(props.getQueue().getDeadLetterKey()), eq("d-1"), value.capture());
        assertThat(value.getValue().toString())
                .startsWith("boom | workflow_run | 2026-01-01T00:00:00Z");
        verify(redis).expire(eq(props.getQueue().getDeadLetterKey()), eq(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("size() 在 Redis 返回 null 时给 0，在异常时给 -1（上层据此展示未知）")
    void sizeReportsUnknownAsMinusOne() {
        when(listOps.size(anyString())).thenReturn(null);
        assertThat(queue.size()).isZero();

        when(listOps.size(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(queue.size()).isEqualTo(-1);
    }

    @Test
    @DisplayName("deadLetter 自身失败不影响主流程")
    void deadLetterFailureIsSwallowed() {
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                .when(hashOps).put(anyString(), any(), any());

        queue.deadLetter(message, "boom");
    }
}
