package com.githublook.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.githublook.common.error.AppException;
import com.githublook.common.web.PageResponse;
import com.githublook.repo.RepoService;
import com.githublook.run.dto.RunDetailDto;
import com.githublook.run.dto.RunFiltersDto;
import com.githublook.run.dto.RunQuery;
import com.githublook.run.dto.RunSummaryDto;
import com.githublook.run.mapper.RunJobMapper;
import com.githublook.run.mapper.RunStepMapper;
import com.githublook.run.mapper.WorkflowRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;

/** Read side of the run aggregate: filterable pagination and full detail (no N+1). */
@Service
@RequiredArgsConstructor
public class RunQueryService {

    private final WorkflowRunMapper runMapper;
    private final RunJobMapper jobMapper;
    private final RunStepMapper stepMapper;
    private final com.githublook.run.log.LogDigestCodec digestCodec;
    private final RepoService repoService;

    public PageResponse<RunSummaryDto> search(RunQuery query) {
        LambdaQueryWrapper<WorkflowRun> wrapper = new LambdaQueryWrapper<>();
        switch (query.normalizedStatusGroup()) {
            case "success" -> wrapper.eq(WorkflowRun::getConclusion, "success");
            case "failure" -> wrapper.in(WorkflowRun::getConclusion,
                    "failure", "timed_out", "startup_failure");
            case "cancelled" -> wrapper.in(WorkflowRun::getConclusion, "cancelled", "skipped", "stale");
            case "running" -> wrapper.in(WorkflowRun::getStatus,
                    "queued", "in_progress", "requested", "waiting", "pending");
            default -> { /* no conclusion filter */ }
        }

        if (query.repo() != null) {
            wrapper.eq(WorkflowRun::getRepoFullName, query.repo());
        }
        if (query.branch() != null) {
            wrapper.eq(WorkflowRun::getBranch, query.branch());
        }
        if (query.workflow() != null) {
            wrapper.eq(WorkflowRun::getWorkflowName, query.workflow());
        }
        if (query.keyword() != null) {
            String kw = query.keyword();
            wrapper.and(w -> w.like(WorkflowRun::getWorkflowName, kw)
                    .or().like(WorkflowRun::getCommitMessage, kw)
                    .or().like(WorkflowRun::getActor, kw)
                    .or().like(WorkflowRun::getBranch, kw)
                    .or().like(WorkflowRun::getCommitSha, kw));
        }
        if (query.from() != null) {
            wrapper.ge(WorkflowRun::getStartedAt, query.from().atStartOfDay());
        }
        if (query.to() != null) {
            wrapper.lt(WorkflowRun::getStartedAt, query.to().plusDays(1).atStartOfDay());
        }

        wrapper.orderByDesc(WorkflowRun::getStartedAt).orderByDesc(WorkflowRun::getId);

        Page<WorkflowRun> page = runMapper.selectPage(new Page<>(query.page(), query.size()), wrapper);
        Map<Long, List<RunSummaryDto.StageBrief>> stagesByRun = loadStageBriefs(page.getRecords());
        List<RunSummaryDto> items = page.getRecords().stream()
                .map(run -> toSummary(run, stagesByRun.getOrDefault(run.getId(), List.of())))
                .toList();
        return PageResponse.of(items, page.getCurrent(), page.getSize(), page.getTotal());
    }

    /**
     * One batched query for the whole page: 20 rows never turn into 20 extra round trips.
     */
    private Map<Long, List<RunSummaryDto.StageBrief>> loadStageBriefs(List<WorkflowRun> runs) {
        if (runs.isEmpty()) {
            return Map.of();
        }
        List<Long> runIds = runs.stream().map(WorkflowRun::getId).toList();
        List<RunJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<RunJob>()
                .in(RunJob::getRunPk, runIds)
                .orderByAsc(RunJob::getId));

        Map<Long, List<RunSummaryDto.StageBrief>> grouped = new LinkedHashMap<>();
        for (RunJob job : jobs) {
            grouped.computeIfAbsent(job.getRunPk(), k -> new ArrayList<>())
                    .add(new RunSummaryDto.StageBrief(job.getId(), job.getName(), job.getStatus(),
                            job.getConclusion(), job.getDurationMs()));
        }
        return grouped;
    }

    public RunDetailDto detail(Long id) {
        WorkflowRun run = runMapper.selectById(id);
        if (run == null) {
            throw AppException.notFound("运行记录");
        }

        List<RunJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<RunJob>()
                .eq(RunJob::getRunPk, id)
                .orderByAsc(RunJob::getId));

        Map<Long, List<RunStep>> stepsByJob = new LinkedHashMap<>();
        if (!jobs.isEmpty()) {
            List<Long> jobIds = jobs.stream().map(RunJob::getId).toList();
            List<RunStep> steps = stepMapper.selectList(new LambdaQueryWrapper<RunStep>()
                    .in(RunStep::getJobPk, jobIds)
                    .orderByAsc(RunStep::getStepNumber)
                    .orderByAsc(RunStep::getId));
            for (RunStep step : steps) {
                stepsByJob.computeIfAbsent(step.getJobPk(), k -> new ArrayList<>()).add(step);
            }
        }

        List<RunDetailDto.JobDto> jobDtos = jobs.stream().map(job -> new RunDetailDto.JobDto(
                job.getId(), job.getName(), job.getStatus(), job.getConclusion(),
                job.getStartedAt(), job.getCompletedAt(), job.getDurationMs(), job.getRunnerName(),
                job.getHtmlUrl(),
                parseNeeds(job.getNeeds()),
                stepsByJob.getOrDefault(job.getId(), List.of()).stream()
                        .map(s -> new RunDetailDto.StepDto(s.getStepNumber(), s.getName(), s.getStatus(),
                                s.getConclusion(), s.getStartedAt(), s.getCompletedAt()))
                        .toList())).toList();

        // 版本最新 → 当前结论；版本落后但能读出来 → 标记 stale 供前端降级展示
        Optional<com.githublook.run.log.LogDigest> fresh =
                digestCodec.decode(run.getFailureDigest(), run.getFailureDigestVersion());
        Optional<com.githublook.run.log.LogDigest> readable =
                fresh.isPresent() ? fresh : digestCodec.decodeAny(run.getFailureDigest());
        boolean failureStale = fresh.isEmpty() && readable.isPresent();

        List<RunDetailDto.FailureHighlightDto> failureHighlights = readable
                .map(d -> d.highlights().stream()
                        .map(h -> new RunDetailDto.FailureHighlightDto(h.title(), h.detail(),
                                h.lineNo(), h.endLine(), h.occurrences()))
                        .toList())
                .orElse(List.of());

        return new RunDetailDto(run.getId(), run.getExternalRunId(), run.getRepoFullName(),
                run.getWorkflowName(), run.getRunNumber(), run.getBranch(), run.getEventName(),
                run.getStatus(), run.getConclusion(), run.getActor(), run.getCommitSha(),
                run.getCommitMessage(), run.getCommitAuthor(), run.getHtmlUrl(), run.getStartedAt(),
                run.getCompletedAt(), run.getDurationMs(), run.getCreatedAt(), run.getUpdatedAt(),
                run.getDeliveryId(),
                run.getFailureClass(), run.getFailureJobName(), run.getFailureSummary(),
                failureHighlights, failureStale, jobDtos);
    }

    public RunFiltersDto filters() {
        List<String> repos = repoService.activeFullNames();
        List<RunFiltersDto.FilterOption> statuses = List.of(
                new RunFiltersDto.FilterOption("all", "全部状态"),
                new RunFiltersDto.FilterOption("success", "成功"),
                new RunFiltersDto.FilterOption("failure", "失败"),
                new RunFiltersDto.FilterOption("running", "运行中"),
                new RunFiltersDto.FilterOption("cancelled", "已取消"));
        return new RunFiltersDto(repos, branchOptions(), statuses);
    }

    /** Branches are derived from real runs, so the filter never offers an empty result set. */
    public List<String> branchOptions() {
        return runMapper.distinctBranches(50);
    }

    /** null = 数据源未提供依赖；空列表 = 已知是根节点 */
    private List<String> parseNeeds(String needs) {
        if (needs == null) {
            return null;
        }
        if (needs.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(needs.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private RunSummaryDto toSummary(WorkflowRun run, List<RunSummaryDto.StageBrief> stages) {
        return new RunSummaryDto(run.getId(), run.getExternalRunId(), run.getRepoFullName(),
                run.getWorkflowName(), run.getRunNumber(), run.getBranch(), run.getEventName(),
                run.getStatus(), run.getConclusion(), run.getActor(), run.getCommitSha(),
                run.getCommitMessage(), run.getCommitAuthor(), run.getHtmlUrl(),
                run.getStartedAt(), run.getCompletedAt(), run.getDurationMs(), stages);
    }
}
