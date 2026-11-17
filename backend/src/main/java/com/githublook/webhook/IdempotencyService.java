package com.githublook.webhook;

import com.githublook.common.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Delivery-id based de-duplication. GitHub retries and manual replays of the same
 * {@code X-GitHub-Delivery} must never create duplicate runs.
 *
 * <p>Redis is the fast path. When Redis is unreachable the method fails open (returns true)
 * because the unique index on {@code webhook_delivery.delivery_id} is the durable backstop.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final StringRedisTemplate redis;
    private final AppProperties props;

    /** @return true when this delivery id was seen for the first time */
    public boolean reserve(String deliveryId) {
        String key = props.getIdempotency().getKeyPrefix() + deliveryId;
        Duration ttl = props.getIdempotency().getTtl();
        try {
            Boolean first = redis.opsForValue().setIfAbsent(key, "1", ttl);
            return Boolean.TRUE.equals(first);
        } catch (RedisConnectionFailureException e) {
            log.warn("idempotency_store_unavailable deliveryId={} falling back to database constraint",
                    deliveryId, e);
            return true;
        }
    }
}
