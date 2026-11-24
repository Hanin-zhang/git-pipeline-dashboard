package com.githublook.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.githublook.run.RunWriteService;
import com.githublook.run.WorkflowRun;
import com.githublook.stats.StatsCache;
import com.githublook.stream.RunEventPayload;
import com.githublook.stream.SseHub;
import com.githublook.webhook.payload.PushEvent;
import com.githublook.webhook.payload.WorkflowRunEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Consumer-side business logic: parse the queued payload, persist the run, push it to the
 * live stream and drop the stats cache. Runs on a virtual thread, never on the web thread.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookEventProcessor {

    private final ObjectMapper objectMapper;
    private final RunWriteService runWriteService;
    private final DeliveryAuditService audit;
    private final SseHub sseHub;
    private final StatsCache statsCache;
    private final com.githublook.settings.AlertNotifier alertNotifier;
    private final com.githublook.github.GithubJobSyncService jobSyncService;
    private final com.githublook.run.RunFailureClassifier failureClassifier;

    public void process(QueueMessage message) {
        switch (message.eventName()) {
            case "workflow_run" -> handleWorkflowRun(message);
            case "push" -> handlePush(message);
            case "workflow_job" -> {
                // Job-level detail is already carried inside our workflow_run payloads; ignore to stay cheap.
                audit.markIgnored(message.deliveryId(), "workflow_job 事件暂不单独落库");
            }
            case "ping" -> audit.markIgnored(message.deliveryId(), "ping 事件仅用于联通性验证");
            default -> audit.markIgnored(message.deliveryId(), "未订阅的事件类型: " + message.eventName());
        }
    }

    private void handleWorkflowRun(QueueMessage message) {
        WorkflowRunEvent event = read(message, WorkflowRunEvent.class);
        if (event.run() == null || event.run().id() == null) {
            throw new PermanentProcessingException("workflow_run 事件缺少 run.id");
        }

        String repoFullName = resolveRepo(message, event.repository() == null ? null : event.repository().fullName());
        RunWriteService.UpsertOutcome outcome =
                runWriteService.upsert(event.run(), repoFullName, message.deliveryId());

        // 真实事件不带 jobs：配置了 token 就顺带把 job/step 拉回来，否则看板只有一个空壳 run
        java.util.List<com.githublook.webhook.payload.GithubJob> payloadJobs = event.run().jobs();
        jobSyncService.fillIfMissing(outcome.run(), payloadJobs != null && !payloadJobs.isEmpty());

        // 失败完成时异步判定失败原因（拉日志 → 解析 → 写 failure_class），不阻塞消费线程
        if (outcome.justFinished() && outcome.run().isFailed()) {
            failureClassifier.classifyAsync(outcome.run());
        }

        statsCache.invalidate();
        sseHub.broadcast(eventName(outcome), toPayload(outcome.run()));
        if (outcome.justFinished() && outcome.run().isFailed()) {
            alertNotifier.runFailed(outcome.run());
        }
        audit.markProcessed(message.deliveryId());

        log.info("run_processed repo={} run={} event={} sse={}",
                repoFullName, outcome.run().getExternalRunId(), message.eventName(), eventName(outcome));
    }

    private void handlePush(QueueMessage message) {
        PushEvent push = read(message, PushEvent.class);
        if (push.repository() == null || push.repository().fullName() == null) {
            throw new PermanentProcessingException("push 事件缺少 repository.full_name");
        }
        String repoFullName = resolveRepo(message, push.repository().fullName());
        String sha = push.after() == null ? null : push.after();
        String author = push.headCommit() != null && push.headCommit().author() != null
                ? push.headCommit().author().name() : null;
        String commitMessage = push.headCommit() == null ? null : push.headCommit().message();

        int touched = runWriteService.enrichCommitInfo(repoFullName, sha, commitMessage, author);
        audit.markProcessed(message.deliveryId());
        log.info("push_processed repo={} branch={} commits={} enriched_runs={}",
                repoFullName, push.branchName(),
                push.commits() == null ? 0 : push.commits().size(), touched);
    }

    private <T> T read(QueueMessage message, Class<T> type) {
        try {
            return objectMapper.readValue(message.payload(), type);
        } catch (Exception e) {
            throw new PermanentProcessingException("无法解析 " + message.eventName() + " payload", e);
        }
    }

    private String resolveRepo(QueueMessage message, String fromPayload) {
        if (fromPayload != null && !fromPayload.equals(message.repoFullName())) {
            log.warn("repo_mismatch deliveryId={} ingest={} payload={}",
                    message.deliveryId(), message.repoFullName(), fromPayload);
        }
        String repo = message.repoFullName() != null ? message.repoFullName() : fromPayload;
        if (repo == null || repo.isBlank()) {
            throw new PermanentProcessingException("无法确定事件所属仓库");
        }
        return repo;
    }

    private String eventName(RunWriteService.UpsertOutcome outcome) {
        WorkflowRun run = outcome.run();
        if (outcome.justFinished()) {
            return run.isFailed() ? "run.failed" : "run.completed";
        }
        if (outcome.created()) {
            return "run.started";
        }
        return "run.updated";
    }

    private RunEventPayload toPayload(WorkflowRun run) {
        return new RunEventPayload(
                run.isFinished() ? (run.isFailed() ? "failed" : "completed")
                        : ("completed".equals(run.getStatus()) ? "completed" : "running"),
                run.getId(),
                run.getRepoFullName(),
                run.getWorkflowName(),
                run.getBranch(),
                run.getStatus(),
                run.getConclusion(),
                run.getActor(),
                run.getCommitMessage(),
                run.getDurationMs(),
                run.getHtmlUrl(),
                run.getUpdatedAt());
    }
}
