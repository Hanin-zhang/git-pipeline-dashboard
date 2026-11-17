package com.githublook.webhook.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/** Job as it appears in the (optional) {@code jobs} array of a synthetic workflow_run payload. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubJob(
        Long id,
        String name,
        String status,
        String conclusion,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("completed_at") Instant completedAt,
        @JsonProperty("runner_name") String runnerName,
        @JsonProperty("html_url") String htmlUrl,
        /** GitHub Actions {@code needs}: names of the jobs that must finish first. */
        java.util.List<String> needs,
        List<GithubStep> steps) {
}
