package com.githublook.webhook.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubRepository(
        Long id,
        String name,
        @JsonProperty("full_name") String fullName,
        GithubOwner owner,
        @JsonProperty("default_branch") String defaultBranch,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("private") Boolean isPrivate) {

    public String ownerLogin() {
        return owner == null ? null : owner.login();
    }
}
