package com.githublook.webhook.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Subset of the {@code workflow_run} webhook payload that we actually persist. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowRunEvent(
        String action,
        @JsonProperty("workflow_run") WorkflowRunData run,
        GithubRepository repository,
        GithubActor sender) {
}
