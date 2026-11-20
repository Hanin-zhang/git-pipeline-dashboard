package com.githublook.stats;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Read-through cache for the dashboard aggregations.
 *
 * <p>Invalidation uses a version counter instead of {@code SCAN}/{@code KEYS}: every write bumps
 * {@code githublook:stats:version}, the version becomes part of the cache key, so all entries
 * created before the write are unreachable at once and simply expire by TTL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatsCache {

    private static final String VERSION_KEY = "githublook:stats:version";
    private static final String PREFIX = "githublook:stats:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public <T> Optional<T> get(String suffix, Class<T> type, Duration ttl) {
        try {
            String key = currentKey(suffix);
            String raw = redis.opsForValue().get(key);
            if (raw == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(raw, type));
        } catch (Exception e) {
            log.debug("stats_cache_read_failed suffix={} reason={}", suffix, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String suffix, Object value, Duration ttl) {
        try {
            String key = currentKey(suffix);
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.debug("stats_cache_write_failed suffix={} reason={}", suffix, e.getMessage());
        }
    }

    /** Bumps the version so every previously cached aggregation is bypassed. */
    public void invalidate() {
        try {
            redis.opsForValue().increment(VERSION_KEY);
        } catch (Exception e) {
            log.debug("stats_cache_invalidate_failed reason={}", e.getMessage());
        }
    }

    private String currentKey(String suffix) {
        String version;
        try {
            version = redis.opsForValue().get(VERSION_KEY);
        } catch (Exception e) {
            version = null;
        }
        return PREFIX + (version == null ? "0" : version) + ":" + suffix;
    }
}
