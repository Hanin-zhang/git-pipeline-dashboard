package com.githublook.repo.dto;

import com.githublook.repo.MonitoredRepo;

import java.time.LocalDateTime;

/**
 * Secret is returned here because the Settings page has to show it for copy/paste into
 * GitHub. The endpoint is authenticated and the value is never written to logs.
 */
public record RepoDto(
        Long id,
        String owner,
        String name,
        String fullName,
        String defaultBranch,
        boolean enabled,
        String secret,
        LocalDateTime createdAt) {

    public static RepoDto from(MonitoredRepo repo) {
        return new RepoDto(
                repo.getId(),
                repo.getOwner(),
                repo.getName(),
                repo.getFullName(),
                repo.getDefaultBranch(),
                Boolean.TRUE.equals(repo.getEnabled()),
                repo.getWebhookSecret(),
                repo.getCreatedAt());
    }
}
