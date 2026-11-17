package com.githublook.webhook.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** Subset of the {@code push} payload. Recorded for audit and commit enrichment. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PushEvent(
        String ref,
        String before,
        String after,
        Boolean created,
        Boolean deleted,
        Boolean forced,
        GithubRepository repository,
        GithubActor pusher,
        GithubActor sender,
        @JsonProperty("head_commit") GithubCommit headCommit,
        @JsonProperty("compare") String compareUrl,
        @JsonProperty("commits") java.util.List<GithubCommit> commits) {

    /** refs/heads/main -> main */
    public String branchName() {
        if (ref == null) {
            return null;
        }
        int idx = ref.lastIndexOf('/');
        return idx >= 0 ? ref.substring(idx + 1) : ref;
    }

    public Instant headCommitTimestamp() {
        return headCommit == null ? null : headCommit.timestamp();
    }
}
