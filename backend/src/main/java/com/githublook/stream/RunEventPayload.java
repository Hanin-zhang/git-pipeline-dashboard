package com.githublook.stream;

import java.time.LocalDateTime;

/** Payload pushed over SSE whenever a run changes state. */
public record RunEventPayload(
        String type,
        Long runId,
        String repoFullName,
        String workflowName,
        String branch,
        String status,
        String conclusion,
        String actor,
        String commitMessage,
        Long durationMs,
        String htmlUrl,
        LocalDateTime at) {
}
