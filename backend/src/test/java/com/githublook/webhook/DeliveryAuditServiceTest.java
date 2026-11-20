package com.githublook.webhook;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.githublook.common.time.TimeMapper;
import com.githublook.support.MybatisLambdaTestSupport;
import com.githublook.webhook.mapper.WebhookDeliveryMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DeliveryAuditService · 投递审计与持久化幂等")
class DeliveryAuditServiceTest {

    @Mock
    private WebhookDeliveryMapper mapper;

    private final TimeMapper time = new TimeMapper("Asia/Shanghai");
    private DeliveryAuditService service;

    @BeforeAll
    static void initMybatisLambdas() {
        MybatisLambdaTestSupport.init(WebhookDelivery.class);
    }

    @BeforeEach
    void setUp() {
        service = new DeliveryAuditService(mapper, time);
    }

    @Test
    @DisplayName("首次投递写入 RECEIVED 审计行")
    void startsNewDelivery() {
        when(mapper.insert(any(WebhookDelivery.class))).thenReturn(1);

        boolean started = service.tryStart("d-1", "workflow_run", "acme/app");

        assertThat(started).isTrue();
        ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(mapper).insert(captor.capture());
        WebhookDelivery saved = captor.getValue();
        assertThat(saved.getDeliveryId()).isEqualTo("d-1");
        assertThat(saved.getStatus()).isEqualTo("RECEIVED");
        assertThat(saved.getEventName()).isEqualTo("workflow_run");
        assertThat(saved.getRepoFullName()).isEqualTo("acme/app");
        assertThat(saved.getReceivedAt()).isNotNull();
    }

    @Test
    @DisplayName("delivery_id 唯一索引冲突 → 返回 false，由调用方判定重复")
    void reportsDuplicateOnUniqueViolation() {
        when(mapper.insert(any(WebhookDelivery.class)))
                .thenThrow(new DataIntegrityViolationException("uk_webhook_delivery_id"));

        assertThat(service.tryStart("d-1", "workflow_run", "acme/app")).isFalse();
    }

    @Test
    @DisplayName("markQueued 只更新状态，不写 processed_at")
    void marksQueued() {
        service.markQueued("d-1");

        ArgumentCaptor<LambdaUpdateWrapper<WebhookDelivery>> captor = wrapperCaptor();
        verify(mapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains("QUEUED");
    }

    @Test
    @DisplayName("markProcessed 更新状态并补 processed_at（两条 update）")
    void marksProcessed() {
        service.markProcessed("d-1");

        verify(mapper, times(2)).update(isNull(), any());
    }

    @Test
    @DisplayName("markFailed 截断超长错误信息，避免撑爆 VARCHAR(1000)")
    void truncatesLongErrorMessages() {
        String longError = "x".repeat(1500);

        service.markFailed("d-1", longError);

        ArgumentCaptor<LambdaUpdateWrapper<WebhookDelivery>> captor = wrapperCaptor();
        verify(mapper, times(2)).update(isNull(), captor.capture());
        String stored = captor.getAllValues().get(0).getParamNameValuePairs().values().stream()
                .filter(v -> v instanceof String s && s.length() > 100)
                .map(Object::toString)
                .findFirst()
                .orElseThrow();
        assertThat(stored).hasSize(900);
    }

    @Test
    @DisplayName("markIgnored 记录原因，便于排查「事件为什么没生效」")
    void marksIgnoredWithReason() {
        service.markIgnored("d-1", "ping 事件仅用于联通性验证");

        ArgumentCaptor<LambdaUpdateWrapper<WebhookDelivery>> captor = wrapperCaptor();
        verify(mapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .contains("IGNORED", "ping 事件仅用于联通性验证");
    }

    @Test
    @DisplayName("统计查询：无数据时返回 0 / null，而不是 NPE")
    void handlesEmptyCounts() {
        when(mapper.selectCount(any())).thenReturn(null);
        when(mapper.selectOne(any())).thenReturn(null);

        assertThat(service.countByStatus("FAILED")).isZero();
        assertThat(service.countSince(LocalDateTime.now().minusDays(1))).isZero();
        assertThat(service.lastReceivedAt()).isNull();
    }

    @Test
    @DisplayName("最后接收时间取自最新一行")
    void returnsLastReceivedAt() {
        LocalDateTime at = LocalDateTime.of(2026, 1, 1, 10, 0);
        WebhookDelivery latest = new WebhookDelivery();
        latest.setReceivedAt(at);
        when(mapper.selectOne(any())).thenReturn(latest);

        assertThat(service.lastReceivedAt()).isEqualTo(at);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<LambdaUpdateWrapper<WebhookDelivery>> wrapperCaptor() {
        return (ArgumentCaptor<LambdaUpdateWrapper<WebhookDelivery>>) (ArgumentCaptor<?>)
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }

    @Test
    @DisplayName("selectCount 返回具体值时原样透出")
    void returnsCounts() {
        when(mapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(7L);

        assertThat(service.countByStatus("PROCESSED")).isEqualTo(7L);
    }
}
