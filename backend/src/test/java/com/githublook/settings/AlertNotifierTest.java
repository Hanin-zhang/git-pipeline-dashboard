package com.githublook.settings;

import com.githublook.run.WorkflowRun;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AlertNotifier · 失败告警（钉钉 / 飞书 / 通用 Webhook）")
class AlertNotifierTest {

    @Mock
    private NotificationService notificationService;

    private HttpServer server;
    private AlertNotifier notifier;

    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicInteger receivedCount = new AtomicInteger();
    private final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
    private final AtomicInteger responseStatus = new AtomicInteger(200);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedCount.incrementAndGet();
            int status = responseStatus.get();
            exchange.sendResponseHeaders(status, status == 200 ? -1 : 0);
            exchange.close();
            latch.get().countDown();
        });
        server.start();
        notifier = new AlertNotifier(notificationService);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private NotificationSetting channel(String name, String notifyOn, String webhookUrl) {
        NotificationSetting setting = new NotificationSetting();
        setting.setId(1L);
        setting.setChannel(name);
        setting.setNotifyOn(notifyOn);
        setting.setWebhookUrl(webhookUrl);
        setting.setEnabled(true);
        return setting;
    }

    private WorkflowRun run() {
        WorkflowRun run = new WorkflowRun();
        run.setId(7L);
        run.setExternalRunId(1234L);
        run.setRepoFullName("acme/app");
        run.setWorkflowName("CI");
        run.setBranch("main");
        run.setConclusion("failure");
        run.setActor("alice");
        run.setCommitMessage("feat: break the build");
        run.setHtmlUrl("https://github.com/acme/app/actions/runs/1234");
        return run;
    }

    private boolean awaitDelivery() throws InterruptedException {
        return latch.get().await(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("没有启用的渠道时什么都不发")
    void doesNothingWithoutChannels() throws InterruptedException {
        when(notificationService.activeChannels()).thenReturn(List.of());

        notifier.runFailed(run());

        assertThat(awaitDelivery()).isFalse();
        assertThat(receivedCount.get()).isZero();
    }

    @Test
    @DisplayName("钉钉渠道发送 markdown 消息，包含仓库/工作流/提交")
    void sendsDingTalkMarkdown() throws Exception {
        when(notificationService.activeChannels())
                .thenReturn(List.of(channel("DINGTALK", "failure", url())));

        notifier.runFailed(run());

        assertThat(awaitDelivery()).isTrue();
        assertThat(receivedBody.get())
                .contains("\"msgtype\":\"markdown\"")
                .contains("构建失败")
                .contains("acme/app")
                .contains("CI")
                .contains("feat: break the build");
    }

    @Test
    @DisplayName("飞书渠道发送 text 消息")
    void sendsFeishuText() throws Exception {
        when(notificationService.activeChannels())
                .thenReturn(List.of(channel("FEISHU", "failure", url())));

        notifier.runFailed(run());

        assertThat(awaitDelivery()).isTrue();
        assertThat(receivedBody.get()).contains("\"msg_type\":\"text\"").contains("acme/app / CI");
    }

    @Test
    @DisplayName("通用 Webhook 发送结构化 JSON，方便对接自建告警")
    void sendsGenericWebhookJson() throws Exception {
        when(notificationService.activeChannels())
                .thenReturn(List.of(channel("WEBHOOK", "failure", url())));

        notifier.runFailed(run());

        assertThat(awaitDelivery()).isTrue();
        assertThat(receivedBody.get())
                .contains("\"event\":\"run.failed\"")
                .contains("\"repo\":\"acme/app\"")
                .contains("\"workflow\":\"CI\"")
                .contains("\"conclusion\":\"failure\"");
    }

    @Test
    @DisplayName("只配置了 success 的渠道不会收到失败告警")
    void skipsChannelsNotInterestedInFailure() throws InterruptedException {
        when(notificationService.activeChannels())
                .thenReturn(List.of(channel("WEBHOOK", "success", url())));

        notifier.runFailed(run());

        assertThat(awaitDelivery()).isFalse();
        assertThat(receivedCount.get()).isZero();
    }

    @Test
    @DisplayName("failure_and_recovery / all 都包含失败场景")
    void supportsCompositeNotifyOptions() throws Exception {
        when(notificationService.activeChannels())
                .thenReturn(List.of(channel("WEBHOOK", "all", url())));

        notifier.runFailed(run());

        assertThat(awaitDelivery()).isTrue();
    }

    @Test
    @DisplayName("Webhook 地址为空的渠道直接跳过，不发无效请求")
    void skipsBlankUrls() throws InterruptedException {
        when(notificationService.activeChannels())
                .thenReturn(List.of(channel("WEBHOOK", "failure", "   ")));

        notifier.runFailed(run());

        assertThat(awaitDelivery()).isFalse();
    }

    @Test
    @DisplayName("告警发送失败只记日志，绝不影响主链路（虚拟线程内吞掉异常）")
    void swallowsDeliveryFailures() throws Exception {
        responseStatus.set(500);
        when(notificationService.activeChannels())
                .thenReturn(List.of(channel("WEBHOOK", "failure", url())));

        notifier.runFailed(run());

        assertThat(awaitDelivery()).isTrue();
        assertThat(receivedCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("超长提交信息截断到 60 字符，避免刷屏")
    void abbreviatesLongCommitMessage() throws Exception {
        when(notificationService.activeChannels())
                .thenReturn(List.of(channel("FEISHU", "failure", url())));
        WorkflowRun run = run();
        run.setCommitMessage("x".repeat(200));

        notifier.runFailed(run);

        assertThat(awaitDelivery()).isTrue();
        assertThat(receivedBody.get()).contains("x".repeat(60) + "...");
    }

    @Test
    @DisplayName("多个渠道各自独立发送")
    void sendsToEveryChannel() throws Exception {
        latch.set(new CountDownLatch(2));
        when(notificationService.activeChannels()).thenReturn(List.of(
                channel("DINGTALK", "failure", url()),
                channel("FEISHU", "failure", url())));

        notifier.runFailed(run());

        assertThat(latch.get().await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedCount.get()).isEqualTo(2);
    }
}
