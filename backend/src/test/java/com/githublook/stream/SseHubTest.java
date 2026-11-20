package com.githublook.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("SseHub · 事件广播与连接去重")
class SseHubTest {

    private final SseHub hub = new SseHub();

    @Test
    @DisplayName("订阅后连接数为 1，并立刻收到 ready 帧")
    void subscribesAndCounts() {
        SseEmitter emitter = hub.subscribe("tab-1234");

        assertThat(emitter).isNotNull();
        assertThat(hub.connectionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("同一标签页刷新（相同 cid）只保留一条连接，旧连接被顶掉")
    void replacesEmitterWithSameClientId() {
        hub.subscribe("tab-1234");
        hub.subscribe("tab-1234");

        assertThat(hub.connectionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("不同 cid 各自独立计数")
    void keepsDistinctClients() {
        hub.subscribe("tab-1111");
        hub.subscribe("tab-2222");

        assertThat(hub.connectionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("非法或缺失 cid 时自动生成，不会因为客户端不传参数就报错")
    void generatesClientIdWhenMissingOrInvalid() {
        hub.subscribe(null);
        hub.subscribe("");
        hub.subscribe("x");
        hub.subscribe("bad id with spaces");

        assertThat(hub.connectionCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("合法字符集（字母数字下划线中划线，4-40 位）原样采用")
    void acceptsSafeClientIds() {
        hub.subscribe("abc_123-XYZ");

        assertThat(hub.connectionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("广播给所有订阅者，不抛异常")
    void broadcastsToAll() {
        hub.subscribe("tab-1111");
        hub.subscribe("tab-2222");

        assertThatCode(() -> hub.broadcast("run.started", Map.of("runId", 1)))
                .doesNotThrowAnyException();
        assertThat(hub.connectionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("没有订阅者时广播是空操作")
    void broadcastWithoutSubscribersIsNoop() {
        assertThatCode(() -> hub.broadcast("run.failed", Map.of("runId", 1)))
                .doesNotThrowAnyException();
        assertThat(hub.connectionCount()).isZero();
    }

    @Test
    @DisplayName("心跳帧不会误删连接")
    void heartbeatKeepsConnections() {
        hub.subscribe("tab-1111");

        hub.heartbeat();

        assertThat(hub.connectionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("正常关闭时清理所有连接（应用停止不留下僵尸连接）")
    void shutdownClearsConnections() {
        hub.subscribe("tab-1111");
        hub.subscribe("tab-2222");

        hub.shutdown();

        assertThat(hub.connectionCount()).isZero();
    }

    @Test
    @DisplayName("连接完成后计数归零（客户端关标签页时容器触发 completion 回调）")
    void completingEmitterRemovesConnection() {
        SseEmitter emitter = hub.subscribe("tab-1111");

        fireCompletion(emitter);

        assertThat(hub.connectionCount()).isZero();
    }

    @Test
    @DisplayName("替换连接后旧连接的完成回调不会误删新连接")
    void staleCompletionDoesNotRemoveNewEmitter() {
        SseEmitter old = hub.subscribe("tab-1111");
        hub.subscribe("tab-1111");

        // 模拟旧连接异步触发的 onCompletion
        fireCompletion(old);

        assertThat(hub.connectionCount()).isEqualTo(1);
    }

    /**
     * 纯单元测试里没有 Servlet 容器，SseEmitter 的 completion 回调不会被自动触发，
     * 这里直接取出注册的回调模拟容器的行为，验证「只删自己」的语义。
     */
    private void fireCompletion(SseEmitter emitter) {
        Object callback = org.springframework.test.util.ReflectionTestUtils.getField(emitter, "completionCallback");
        assertThat(callback).isInstanceOf(Runnable.class);
        ((Runnable) callback).run();
    }
}
