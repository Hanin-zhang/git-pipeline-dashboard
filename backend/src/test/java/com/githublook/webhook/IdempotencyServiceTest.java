package com.githublook.webhook;

import com.githublook.common.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyService · delivery 去重")
class IdempotencyServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private final AppProperties props = new AppProperties();
    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(redis, props);
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("首次投递：SET NX 成功即为首次")
    void reservesFirstDelivery() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        assertThat(service.reserve("delivery-1")).isTrue();
    }

    @Test
    @DisplayName("重复投递：SET NX 失败即判定为重复")
    void rejectsDuplicateDelivery() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(service.reserve("delivery-1")).isFalse();
    }

    @Test
    @DisplayName("Redis 返回 null（连接被中断等边界）时按重复处理，不放大流量")
    void nullResultIsTreatedAsDuplicate() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);

        assertThat(service.reserve("delivery-1")).isFalse();
    }

    @Test
    @DisplayName("Redis 不可用时 fail-open（返回 true），由数据库唯一索引兜底")
    void failsOpenWhenRedisIsDown() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThat(service.reserve("delivery-1")).isTrue();
    }

    @Test
    @DisplayName("键名带上配置前缀，TTL 使用配置值")
    void usesConfiguredPrefixAndTtl() {
        props.getIdempotency().setKeyPrefix("test:delivery:");
        props.getIdempotency().setTtl(Duration.ofMinutes(5));
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        service.reserve("abc");

        verify(valueOps).setIfAbsent(eq("test:delivery:abc"), eq("1"), eq(Duration.ofMinutes(5)));
    }
}
