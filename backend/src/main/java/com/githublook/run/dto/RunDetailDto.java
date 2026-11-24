package com.githublook.run.dto;

import java.time.LocalDateTime;
import java.util.List;

public record RunDetailDto(
        Long id,
        Long externalRunId,
        String repoFullName,
        String workflowName,
        Integer runNumber,
        String branch,
        String eventName,
        String status,
        String conclusion,
        String actor,
        String commitSha,
        String commitMessage,
        String commitAuthor,
        String htmlUrl,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        Long durationMs,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String deliveryId,
        /** 失败原因类别（test/build/...），未归类为 null */
        String failureClass,
        /** 摘要来自哪个 job 的日志 */
        String failureJobName,
        /** 一句话结论，详情页红底高亮展示 */
        String failureSummary,
        /** 关键错误块（不含上下文，体积小） */
        List<FailureHighlightDto> failureHighlights,
        /** true = 摘要由旧版解析规则生成，仅作参考，建议重新分析 */
        boolean failureStale,
        List<JobDto> jobs) {

    public record FailureHighlightDto(String title, String detail, int lineNo, int endLine, int occurrences) {
    }

    public record JobDto(
            Long id,
            String name,
            String status,
            String conclusion,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            Long durationMs,
            String runnerName,
            String htmlUrl,
            /** Real GitHub Actions {@code needs}; empty when the source event carried no dependency info. */
            List<String> needs,
            List<StepDto> steps) {
    }

    public record StepDto(
            Integer number,
            String name,
            String status,
            String conclusion,
            LocalDateTime startedAt,
            LocalDateTime completedAt) {
    }
}
