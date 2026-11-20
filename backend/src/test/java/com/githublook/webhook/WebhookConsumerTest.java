package com.githublook.webhook;

import com.githublook.common.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WebhookConsumer · 消费与重试策略")
class WebhookConsumerTest {

    @Mock
    private WebhookQueue queue;
    @Mock
    private WebhookEventProcessor processor;
    @Mock
    private DeliveryAuditService audit;

    private final AppProperties props = new AppProperties();
    private WebhookConsumer consumer;

    private final QueueMessage message = new QueueMessage("d-1", "workflow_run", "acme/app",
            "{\"x\":1}", 0, Instant.parse("2026-01-01T00:00:00Z"));

    @BeforeEach
    void setUp() {
        props.getQueue().setConsumers(1);
        props.getQueue().setMaxAttempts(3);
        consumer = new WebhookConsumer(queue, processor, audit, props);
        when(queue.poll()).thenReturn(Optional.empty());
    }

    private void dispatch(QueueMessage message) {
        ReflectionTestUtils.invokeMethod(consumer, "dispatch", message);
    }

    @Test
    @DisplayName("处理成功：计数 +1，不写死信、不重投")
    void countsSuccessfulMessage() {
        dispatch(message);

        assertThat(consumer.processedCount()).isEqualTo(1);
        assertThat(consumer.failedCount()).isZero();
        verify(queue, never()).deadLetter(any(), anyString());
        verify(queue, never()).enqueue(any());
    }

    @Test
    @DisplayName("PermanentProcessingException 立即进死信，不做无意义的 3 次重试")
    void permanentFailureGoesStraightToDeadLetter() {
        doThrow(new PermanentProcessingException("payload 缺 run.id"))
                .when(processor).process(any(QueueMessage.class));

        dispatch(message);

        assertThat(consumer.failedCount()).isEqualTo(1);
        verify(queue).deadLetter(message, "payload 缺 run.id");
        verify(audit).markFailed("d-1", "payload 缺 run.id");
        verify(queue, never()).enqueue(any());
    }

    @Test
    @DisplayName("第一次临时失败：attempt 0 → 1，重新入队")
    void retriesFirstFailure() {
        doThrow(new IllegalStateException("db timeout")).when(processor).process(any(QueueMessage.class));

        dispatch(message);

        ArgumentCaptor<QueueMessage> captor = ArgumentCaptor.forClass(QueueMessage.class);
        verify(queue).enqueue(captor.capture());
        assertThat(captor.getValue().attempt()).isEqualTo(1);
        assertThat(captor.getValue().deliveryId()).isEqualTo("d-1");
        verify(queue, never()).deadLetter(any(), anyString());
    }

    @Test
    @DisplayName("第二次临时失败仍可重试（attempt 1 → 2）")
    void retriesSecondFailure() {
        doThrow(new IllegalStateException("db timeout")).when(processor).process(any(QueueMessage.class));

        dispatch(message.nextAttempt());

        ArgumentCaptor<QueueMessage> captor = ArgumentCaptor.forClass(QueueMessage.class);
        verify(queue).enqueue(captor.capture());
        assertThat(captor.getValue().attempt()).isEqualTo(2);
    }

    @Test
    @DisplayName("达到 max-attempts：第 3 次仍失败 → 死信 + 审计 FAILED，不再重投")
    void givesUpAfterMaxAttempts() {
        doThrow(new IllegalStateException("db timeout")).when(processor).process(any(QueueMessage.class));

        dispatch(message.nextAttempt().nextAttempt());

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(queue).deadLetter(eq(message.nextAttempt().nextAttempt()), reason.capture());
        assertThat(reason.getValue()).contains("IllegalStateException").contains("db timeout");
        verify(audit).markFailed(eq("d-1"), contains("db timeout"));
        verify(queue, never()).enqueue(any());
    }

    @Test
    @DisplayName("start 按配置数量拉起虚拟线程，stop 后状态归零")
    void lifecycleStartsAndStopsWorkers() {
        props.getQueue().setConsumers(2);

        consumer.start();
        assertThat(consumer.isRunning()).isTrue();

        consumer.stop();
        assertThat(consumer.isRunning()).isFalse();
    }

    @Test
    @DisplayName("重复 start/stop 不抛异常（容器刷新场景）")
    void lifecycleIsIdempotent() {
        consumer.start();
        consumer.start();
        consumer.stop();
        consumer.stop();
    }

    @Test
    @DisplayName("poll 抛异常时消费循环不会退出（记录后继续）")
    void loopSurvivesPollFailure() {
        when(queue.poll()).thenThrow(new RuntimeException("redis blip")).thenReturn(Optional.empty());

        consumer.start();
        consumer.stop();

        assertThat(consumer.isRunning()).isFalse();
    }
}
