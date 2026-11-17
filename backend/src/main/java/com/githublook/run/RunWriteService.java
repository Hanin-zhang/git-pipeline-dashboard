package com.githublook.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.githublook.common.time.TimeMapper;
import com.githublook.run.mapper.RunJobMapper;
import com.githublook.run.mapper.RunStepMapper;
import com.githublook.run.mapper.WorkflowRunMapper;
import com.githublook.webhook.payload.GithubJob;
import com.githublook.webhook.payload.GithubStep;
import com.githublook.webhook.payload.WorkflowRunData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Write side of the run aggregate. Upserts are idempotent on
 * {@code (repo_full_name, external_run_id)} so a replayed delivery converges to the same state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunWriteService {

    private static final int MAX_MESSAGE = 900;

    private final WorkflowRunMapper runMapper;
    private final RunJobMapper jobMapper;
    private final RunStepMapper stepMapper;
    private final TimeMapper time;

    /** @return the persisted run plus whether this delivery created / finished it */
    public record UpsertOutcome(WorkflowRun run, boolean created, boolean justFinished) {
    }

    @Transactional
    public UpsertOutcome upsert(WorkflowRunData data, String repoFullName, String deliveryId) {
        WorkflowRun existing = runMapper.selectOne(new LambdaQueryWrapper<WorkflowRun>()
                .eq(WorkflowRun::getRepoFullName, repoFullName)
                .eq(WorkflowRun::getExternalRunId, data.id())
                .last("LIMIT 1"));

        boolean created = existing == null;
        boolean wasFinished = !created && existing.isFinished();
        WorkflowRun run = created ? new WorkflowRun() : existing;

        run.setExternalRunId(data.id());
        run.setRepoFullName(repoFullName);
        run.setWorkflowName(data.name());
        run.setRunNumber(data.runNumber());
        run.setBranch(data.headBranch());
        run.setEventName(data.event());
        run.setStatus(data.status() == null ? "unknown" : data.status());
        run.setConclusion(data.conclusion());
        run.setActor(firstNonNull(
                data.triggeringActor() == null ? null : data.triggeringActor().login(),
                data.actor() == null ? null : data.actor().login()));
        run.setCommitSha(firstNonNull(data.headSha(),
                data.headCommit() == null ? null : data.headCommit().id()));
        if (data.headCommit() != null) {
            run.setCommitMessage(truncate(data.headCommit().message(), MAX_MESSAGE));
            if (data.headCommit().author() != null) {
                run.setCommitAuthor(data.headCommit().author().name());
            }
        }
        run.setHtmlUrl(data.htmlUrl());
        run.setDeliveryId(deliveryId);

        LocalDateTime startedAt = time.toLocal(firstNonNull(data.runStartedAt(), data.createdAt()));
        if (startedAt != null) {
            run.setStartedAt(startedAt);
        }
        if ("completed".equals(run.getStatus())) {
            LocalDateTime completedAt = time.toLocal(data.updatedAt());
            run.setCompletedAt(completedAt == null ? time.now() : completedAt);
            if (run.getStartedAt() != null) {
                run.setDurationMs(Math.max(0,
                        Duration.between(run.getStartedAt(), run.getCompletedAt()).toMillis()));
            }
        }

        LocalDateTime now = time.now();
        run.setUpdatedAt(now);
        if (created) {
            run.setCreatedAt(now);
            runMapper.insert(run);
        } else {
            runMapper.updateById(run);
        }

        if (data.jobs() != null && !data.jobs().isEmpty()) {
            replaceJobs(run, data.jobs());
        }

        boolean justFinished = run.isFinished() && !wasFinished;
        log.info("run_upserted repo={} run={} status={} conclusion={} created={}",
                repoFullName, data.id(), run.getStatus(), run.getConclusion(), created);
        return new UpsertOutcome(run, created, justFinished);
    }

    private void replaceJobs(WorkflowRun run, List<GithubJob> jobs) {
        List<RunJob> previous = jobMapper.selectList(new LambdaQueryWrapper<RunJob>()
                .eq(RunJob::getRunPk, run.getId()));
        if (!previous.isEmpty()) {
            List<Long> jobIds = previous.stream().map(RunJob::getId).toList();
            stepMapper.delete(new LambdaQueryWrapper<RunStep>().in(RunStep::getJobPk, jobIds));
            jobMapper.delete(new LambdaQueryWrapper<RunJob>().eq(RunJob::getRunPk, run.getId()));
        }

        for (GithubJob payload : jobs) {
            RunJob job = new RunJob();
            job.setRunPk(run.getId());
            job.setExternalJobId(payload.id());
            job.setName(payload.name());
            job.setStatus(payload.status());
            job.setConclusion(payload.conclusion());
            LocalDateTime jobStart = time.toLocal(payload.startedAt());
            LocalDateTime jobEnd = time.toLocal(payload.completedAt());
            job.setStartedAt(jobStart);
            job.setCompletedAt(jobEnd);
            if (jobStart != null && jobEnd != null) {
                job.setDurationMs(Math.max(0, Duration.between(jobStart, jobEnd).toMillis()));
            }
            job.setRunnerName(payload.runnerName());
            job.setHtmlUrl(payload.htmlUrl());
            job.setNeeds(joinNeeds(payload.needs()));
            jobMapper.insert(job);

            if (payload.steps() != null) {
                for (GithubStep stepPayload : payload.steps()) {
                    RunStep step = new RunStep();
                    step.setJobPk(job.getId());
                    step.setStepNumber(stepPayload.number());
                    step.setName(stepPayload.name());
                    step.setStatus(stepPayload.status());
                    step.setConclusion(stepPayload.conclusion());
                    step.setStartedAt(time.toLocal(stepPayload.startedAt()));
                    step.setCompletedAt(time.toLocal(stepPayload.completedAt()));
                    stepMapper.insert(step);
                }
            }
        }
    }

    /**
     * A {@code push} event carries the commit message that the workflow_run payload may be
     * missing; fill it in for runs of the same commit without overwriting anything.
     *
     * @return number of rows touched
     */
    @Transactional
    public int enrichCommitInfo(String repoFullName, String sha, String message, String author) {
        if (sha == null || (message == null && author == null)) {
            return 0;
        }
        WorkflowRun patch = new WorkflowRun();
        if (message != null) {
            patch.setCommitMessage(truncate(message, MAX_MESSAGE));
        }
        if (author != null) {
            patch.setCommitAuthor(author);
        }
        patch.setUpdatedAt(time.now());
        return runMapper.update(patch, new LambdaUpdateWrapper<WorkflowRun>()
                .eq(WorkflowRun::getRepoFullName, repoFullName)
                .eq(WorkflowRun::getCommitSha, sha));
    }

    /**
     * 区分三种情况：
     * <ul>
     *   <li>payload 没有 needs 字段 → 存 null，表示「依赖关系未知」</li>
     *   <li>payload 的 needs 为空数组 → 存空串，表示「已知无依赖（根节点）」</li>
     *   <li>有依赖 → 存逗号分隔的 job 名</li>
     * </ul>
     */
    private String joinNeeds(java.util.List<String> needs) {
        if (needs == null) {
            return null;
        }
        String joined = needs.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return joined.length() > 1000 ? joined.substring(0, 1000) : joined;
    }

    private static <T> T firstNonNull(T a, T b) {
        return a != null ? a : b;
    }
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
