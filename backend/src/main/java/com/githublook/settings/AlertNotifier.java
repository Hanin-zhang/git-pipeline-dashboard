package com.githublook.settings;

import com.githublook.run.WorkflowRun;
import com.githublook.settings.dto.NotificationDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Optional alerting: when a run fails, post to the configured DingTalk / Feishu / generic
 * webhook. Strictly fire-and-forget on its own virtual thread — alerting must never be able
 * to slow down or fail webhook ingestion.
 */
@Slf4j
@Service
public class AlertNotifier {

    private final NotificationService notificationService;
    private final RestClient restClient;

    public AlertNotifier(NotificationService notificationService) {
        this.notificationService = notificationService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public void runFailed(WorkflowRun run) {
        List<NotificationSetting> channels = notificationService.activeChannels().stream()
                .filter(c -> "failure".equals(c.getNotifyOn()) || "failure_and_recovery".equals(c.getNotifyOn())
                        || "all".equals(c.getNotifyOn()))
                .filter(c -> c.getWebhookUrl() != null && !c.getWebhookUrl().isBlank())
                .toList();
        if (channels.isEmpty()) {
            return;
        }
        Thread.ofVirtual().name("alert-" + run.getId()).start(() -> channels.forEach(c -> send(c, run)));
    }

    private void send(NotificationSetting channel, WorkflowRun run) {
        try {
            Object payload = switch (channel.getChannel()) {
                case "DINGTALK" -> Map.of(
                        "msgtype", "markdown",
                        "markdown", Map.of(
                                "title", "构建失败: " + run.getWorkflowName(),
                                "text", renderMarkdown(run)));
                case "FEISHU" -> Map.of(
                        "msg_type", "text",
                        "content", Map.of("text", renderText(run)));
                default -> Map.of(
                        "event", "run.failed",
                        "repo", run.getRepoFullName(),
                        "workflow", run.getWorkflowName(),
                        "branch", nullToEmpty(run.getBranch()),
                        "conclusion", nullToEmpty(run.getConclusion()),
                        "actor", nullToEmpty(run.getActor()),
                        "htmlUrl", nullToEmpty(run.getHtmlUrl()));
            };

            restClient.post()
                    .uri(channel.getWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload == null ? Map.of() : payload)
                    .retrieve()
                    .toBodilessEntity();
            log.info("alert_sent channel={} run={}", channel.getChannel(), run.getExternalRunId());
        } catch (Exception e) {
            // Deliberately swallowed: a broken robot must not break the pipeline view.
            log.warn("alert_failed channel={} run={} reason={}",
                    channel.getChannel(), run.getExternalRunId(), e.toString());
        }
    }

    private String renderMarkdown(WorkflowRun run) {
        return "### 构建失败\n\n"
                + "- 仓库: " + run.getRepoFullName() + "\n"
                + "- 工作流: " + run.getWorkflowName() + "\n"
                + "- 分支: " + nullToEmpty(run.getBranch()) + "\n"
                + "- 触发人: " + nullToEmpty(run.getActor()) + "\n"
                + "- 提交: " + abbreviate(run.getCommitMessage()) + "\n"
                + "- [查看运行](" + nullToEmpty(run.getHtmlUrl()) + ")";
    }

    private String renderText(WorkflowRun run) {
        return "构建失败 " + run.getRepoFullName() + " / " + run.getWorkflowName()
                + " @ " + nullToEmpty(run.getBranch())
                + " 提交: " + abbreviate(run.getCommitMessage())
                + " " + nullToEmpty(run.getHtmlUrl());
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "-";
        }
        return value.length() <= 60 ? value : value.substring(0, 60) + "...";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
