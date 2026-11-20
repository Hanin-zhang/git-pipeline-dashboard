package com.githublook.repo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 接入仓库的入参。
 *
 * <p>{@code fullName} 这里只做「非空 + 长度」这类边界校验，
 * 具体的格式归一化（owner/repo、完整 GitHub 链接、SSH 形式）交给
 * {@code RepoService.normalizeFullName} —— 放在 DTO 里用 @Pattern 会把
 * URL 形式直接拒掉，也没法给出「解析后是什么」的友好提示。
 */
public record CreateRepoRequest(
        @NotBlank(message = "仓库地址不能为空")
        @Size(max = 300, message = "仓库地址过长")
        String fullName,

        @Size(max = 160)
        String defaultBranch,

        /** Optional; generated with a secure random source when blank. */
        @Size(max = 200)
        String secret) {
}
