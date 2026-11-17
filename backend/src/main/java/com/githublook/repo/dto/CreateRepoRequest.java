package com.githublook.repo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateRepoRequest(
        @NotBlank(message = "仓库全名不能为空")
        @Pattern(regexp = "^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$", message = "仓库全名格式应为 owner/repo")
        @Size(max = 280)
        String fullName,

        @Size(max = 160)
        String defaultBranch,

        /** Optional; generated with a secure random source when blank. */
        @Size(max = 200)
        String secret) {
}
