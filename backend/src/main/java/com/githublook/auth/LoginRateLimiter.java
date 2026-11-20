package com.githublook.auth;

import com.githublook.common.error.AppException;
import com.githublook.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-process guard against credential stuffing: 10 failed logins per client per
 * 5 minutes. Single-instance scope is deliberate — a shared limiter would need Redis, and
 * for one dashboard the blast radius is nil.
 */
@Component
@RequiredArgsConstructor
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 10;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();

    public void check(String client) {
        Deque<Instant> queue = attempts.get(client);
        if (queue == null) {
            return;
        }
        Instant threshold = Instant.now().minus(WINDOW);
        synchronized (queue) {
            while (!queue.isEmpty() && queue.peekFirst().isBefore(threshold)) {
                queue.pollFirst();
            }
            if (queue.size() >= MAX_ATTEMPTS) {
                throw new AppException(ErrorCode.RATE_LIMITED, "登录尝试过于频繁，请 5 分钟后再试");
            }
        }
    }

    public void recordFailure(String client) {
        Deque<Instant> queue = attempts.computeIfAbsent(client, k -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(Instant.now());
        }
    }

    public void reset(String client) {
        attempts.remove(client);
    }
}
