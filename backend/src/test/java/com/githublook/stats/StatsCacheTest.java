package com.githublook.stats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.githublook.stats.dto.TrendPointDto;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StatsCache · 版本号失效的读穿缓存")
class StatsCacheTest {

    private static final String VERSION_KEY = "githublook:stats:version";

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Duration ttl = Duration.ofSeconds(20);
    private StatsCache cache;

    private final TrendPointDto point = new TrendPointDto("2026-01-01", "01-01", 10, 9, 1, 90.0, 1234);

    @BeforeEach
    void setUp() {
        cache = new StatsCache(redis, objectMapper);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(VERSION_KEY)).thenReturn("3");
    }

    @Test
    @DisplayName("未命中返回 empty，交给调用方回源")
    void returnsEmptyOnMiss() {
        when(valueOps.get("githublook:stats:3:summary:-:-")).thenReturn(null);

        assertThat(cache.get("summary:-:-", TrendPointDto.class, ttl)).isEmpty();
    }

    @Test
    @DisplayName("命中返回反序列化后的对象")
    void returnsCachedValue() throws Exception {
        when(valueOps.get("githublook:stats:3:trend")).thenReturn(objectMapper.writeValueAsString(point));

        Optional<TrendPointDto> cached = cache.get("trend", TrendPointDto.class, ttl);

        assertThat(cached).contains(point);
    }

    @Test
    @DisplayName("写入使用「版本号 + suffix」作为 key，并带上 TTL")
    void writesVersionedKeyWithTtl() throws Exception {
        cache.put("summary:-:-", point, ttl);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("githublook:stats:3:summary:-:-"), json.capture(), eq(ttl));
        assertThat(objectMapper.readValue(json.getValue(), TrendPointDto.class)).isEqualTo(point);
    }

    @Test
    @DisplayName("失效只 INCR 版本号：旧 key 不会被删除，但立刻不可达")
    void invalidateBumpsVersionOnly() {
        cache.invalidate();

        verify(valueOps).increment(VERSION_KEY);
        verify(redis, org.mockito.Mockito.never()).delete(anyString());
    }

    @Test
    @DisplayName("版本号变化后 key 随之变化（旧缓存自然过期）")
    void keyChangesWhenVersionBumps() {
        cache.put("summary", point, ttl);
        when(valueOps.get(VERSION_KEY)).thenReturn("4");
        cache.put("summary", point, ttl);

        verify(valueOps).set(eq("githublook:stats:3:summary"), anyString(), eq(ttl));
        verify(valueOps).set(eq("githublook:stats:4:summary"), anyString(), eq(ttl));
    }

    @Test
    @DisplayName("版本号 key 不存在时按 0 处理")
    void treatsMissingVersionAsZero() {
        when(valueOps.get(VERSION_KEY)).thenReturn(null);
        when(valueOps.get("githublook:stats:0:summary")).thenReturn("{}");

        assertThat(cache.get("summary", TrendPointDto.class, ttl)).isPresent();
    }

    @Test
    @DisplayName("Redis 读失败 → 降级为未命中，不抛异常")
    void readFailureDegradesToMiss() {
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.get("summary", TrendPointDto.class, ttl)).isEmpty();
    }

    @Test
    @DisplayName("Redis 写失败 → 静默忽略，业务结果照常返回")
    void writeFailureIsSwallowed() {
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                .when(valueOps).set(anyString(), anyString(), eq(ttl));

        cache.put("summary", point, ttl);
    }

    @Test
    @DisplayName("失效失败也不影响主流程（写操作已成功，只是缓存多活一会儿）")
    void invalidateFailureIsSwallowed() {
        when(valueOps.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        cache.invalidate();
    }

    @Test
    @DisplayName("缓存里是坏 JSON → 当作未命中，而不是 500")
    void corruptCacheIsTreatedAsMiss() {
        when(valueOps.get("githublook:stats:3:summary")).thenReturn("{bad-json");

        assertThat(cache.get("summary", TrendPointDto.class, ttl)).isEmpty();
    }
}
