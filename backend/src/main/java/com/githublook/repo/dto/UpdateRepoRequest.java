package com.githublook.repo.dto;

import jakarta.validation.constraints.Size;

public record UpdateRepoRequest(
        @Size(max = 160) String defaultBranch,
        Boolean enabled,
        @Size(max = 200) String secret) {
}
