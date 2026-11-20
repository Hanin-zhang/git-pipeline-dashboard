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

    /** 30 分钟兜底：即使客户端是「半死不活」的连接，也会被容器回收再自动重连。 */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    /**
     * @param requestedId 浏览器传上来的稳定标识（同一标签页刷新后不变）。
     *                    相同 id 会顶掉旧连接，避免刷新一次就多一条僵尸连接。
     */
    public SseEmitter subscribe(String requestedId) {
        String clientId = normalizeClientId(requestedId);

        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);

        // 只删「自己」：用 remove(key, value) 做身份校验。
        // 关键点：旧连接的 onCompletion 是异步触发的，如果只按 clientId 删，
        // 它会把刚刚顶替上来的新连接误删掉（表现为连接数莫名归零）。
        emitter.onCompletion(() -> removeIfSame(clientId, emitter, "completed"));
        emitter.onTimeout(() -> {
            removeIfSame(clientId, emitter, "timeout");
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // 已经结束
            }
        });
        emitter.onError(e -> removeIfSame(clientId, emitter, "error:" + e.getClass().getSimpleName()));

        // 原子替换：put 返回旧值，随后再关闭旧连接，顺序不能颠倒
        SseEmitter previous = emitters.put(clientId, emitter);
        if (previous != null) {
            try {
                previous.complete();
            } catch (RuntimeException ignored) {
                // 旧连接可能已经断了
            }
            log.info("sse_replaced clientId={} connections={}", clientId, emitters.size());
        }

        try {
            emitter.send(SseEmitter.event().name("ready").data(Map.of(
                    "clientId", clientId,
                    "connections", emitters.size())));
        } catch (IOException e) {
            removeIfSame(clientId, emitter, "ready-send-failed");
        }
        log.info("sse_subscribed clientId={} connections={}", clientId, emitters.size());
        return emitter;
    }

    /** 只接受安全字符；不合法或缺失时退回自增 id。 */
    private String normalizeClientId(String requested) {
        if (requested != null && requested.matches("[A-Za-z0-9_-]{4,40}")) {
            return requested;
        }
        return "c" + sequence.incrementAndGet();
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
                removeIfSame(clientId, emitter, "send-failed");
            }
        });
    }

    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        emitters.forEach((clientId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException e) {
                removeIfSame(clientId, emitter, "heartbeat-failed");
            }
        });
    }

    public int connectionCount() {
        return emitters.size();
    }

    /** 只有当前 key 仍然映射到这个 emitter 时才移除，避免误删同 id 的新连接。 */
    private void removeIfSame(String clientId, SseEmitter emitter, String reason) {
        if (emitters.remove(clientId, emitter)) {
            log.info("sse_removed clientId={} reason={} connections={}", clientId, reason, emitters.size());
        }
    }

    @PreDestroy
    void shutdown() {
        emitters.values().forEach(SseEmitter::complete);
        emitters.clear();
    }
}
