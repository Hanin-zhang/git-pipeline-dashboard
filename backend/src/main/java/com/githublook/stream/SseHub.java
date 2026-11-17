package com.githublook.stream;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fan-out hub for Server-Sent Events. Every browser tab holds one emitter; run state changes
 * are pushed to all of them within milliseconds, so the dashboard never needs to poll.
 *
 * <p>A comment frame is emitted every 25 seconds: it keeps Nginx/proxies from closing an idle
 * connection and lets the client detect a dead stream quickly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseHub {

    private static final long NO_TIMEOUT = 0L;

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public SseEmitter subscribe() {
        String clientId = "c" + sequence.incrementAndGet();
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);

        emitter.onCompletion(() -> remove(clientId, "completed"));
        emitter.onTimeout(() -> remove(clientId, "timeout"));
        emitter.onError(e -> remove(clientId, "error:" + e.getClass().getSimpleName()));

        emitters.put(clientId, emitter);
        try {
            emitter.send(SseEmitter.event().name("ready").data(Map.of(
                    "clientId", clientId,
                    "connections", emitters.size())));
        } catch (IOException e) {
            remove(clientId, "ready-send-failed");
        }
        log.info("sse_subscribed clientId={} connections={}", clientId, emitters.size());
        return emitter;
    }

    /** Broadcasts to every subscriber; dead emitters are dropped as they are discovered. */
    public void broadcast(String eventName, Object payload) {
        if (emitters.isEmpty()) {
            return;
        }
        emitters.forEach((clientId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException | IllegalStateException e) {
                remove(clientId, "send-failed");
            }
        });
    }

    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        emitters.forEach((clientId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException e) {
                remove(clientId, "heartbeat-failed");
            }
        });
    }

    public int connectionCount() {
        return emitters.size();
    }

    private void remove(String clientId, String reason) {
        if (emitters.remove(clientId) != null) {
            log.info("sse_removed clientId={} reason={} connections={}", clientId, reason, emitters.size());
        }
    }

    @PreDestroy
    void shutdown() {
        emitters.values().forEach(SseEmitter::complete);
        emitters.clear();
    }
}
