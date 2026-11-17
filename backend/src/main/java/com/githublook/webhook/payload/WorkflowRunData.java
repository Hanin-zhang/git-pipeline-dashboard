package com.githublook.webhook.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * The {@code workflow_run} object. GitHub does not include jobs in this event, so the
 * {@code jobs} field is optional: the local simulator sends it, and a real deployment can
 * subscribe to {@code workflow_job} events to fill the run detail page.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowRunData(
        Long id,
        String name,
        @JsonProperty("head_branch") String headBranch,
        @JsonProperty("head_sha") String headSha,
        @JsonProperty("run_number") Integer runNumber,
        @JsonProperty("run_attempt") Integer runAttempt,
        String event,
        String status,
        String conclusion,
        @JsonProperty("run_started_at") Instant runStartedAt,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("html_url") String htmlUrl,
        GithubActor actor,
        @JsonProperty("triggering_actor") GithubActor triggeringActor,
        @JsonProperty("head_commit") GithubCommit headCommit,
        List<GithubJob> jobs) {
}
