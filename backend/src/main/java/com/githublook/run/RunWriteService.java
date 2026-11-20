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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        // 迟到的旧事件不能把已结束的运行拉回「运行中」。
        // 并发消费 + GitHub 偶发乱序时，in_progress 事件可能晚于 completed 被处理；
        // 由于 updateById 会忽略 null 字段，这种回退会留下
        // status=in_progress + conclusion=success 的矛盾状态（看板显示一直在跑、趋势图统计不到）。
        boolean incomingFinished = "completed".equalsIgnoreCase(data.status());
        boolean alreadyConcluded = !created
                && (existing.isFinished() || existing.getConclusion() != null || existing.getCompletedAt() != null);
        if (alreadyConcluded && !incomingFinished) {
            log.warn("run_status_regression_ignored repo={} run={} kept={} incoming={}",
                    repoFullName, data.id(), existing.getStatus(), data.status());
            return new UpsertOutcome(existing, false, false);
        }

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
            // 上面的 alreadyConcluded 检查基于「先读后判」，两个并发事务可能都读到旧值而同时放行。
            // 这里把判断下沉成 UPDATE 的 WHERE 条件，让数据库保证原子性：
            // 任何「非完成态」的写入都不允许落到已经产出结论的行上。
            LambdaUpdateWrapper<WorkflowRun> guard = new LambdaUpdateWrapper<WorkflowRun>()
                    .eq(WorkflowRun::getId, run.getId());
            if (!incomingFinished) {
                guard.and(w -> w.ne(WorkflowRun::getStatus, "completed")
                        .or().isNull(WorkflowRun::getConclusion));
            }
            int updated = runMapper.update(run, guard);
            if (updated == 0) {
                log.warn("run_update_rejected_stale repo={} run={} incomingStatus={}",
                        repoFullName, data.id(), data.status());
                WorkflowRun fresh = runMapper.selectById(existing.getId());
                return new UpsertOutcome(fresh == null ? existing : fresh, false, false);
            }
        }

        if (data.jobs() != null && !data.jobs().isEmpty()) {
            replaceJobs(run, data.jobs());
        }

        boolean justFinished = run.isFinished() && !wasFinished;
        log.info("run_upserted repo={} run={} status={} conclusion={} created={}",
                repoFullName, data.id(), run.getStatus(), run.getConclusion(), created);
        return new UpsertOutcome(run, created, justFinished);
    }

    /**
     * 同步该 run 的 job / step 明细。
     *
     * <p>按 {@code external_job_id} 做增量 upsert，而不是「全删再全插」：
     * <ul>
     *   <li>id 稳定 —— 前端抽屉、SSE 刷新时不会因为主键漂移而指向不存在的行</li>
     *   <li>一次运行会陆续收到 queued / in_progress / completed 多个事件，
     *       每个事件都全删全插会让 run_job 的自增键暴涨，并制造大量无谓的 DELETE</li>
     *   <li>两个消费者并发处理同一次运行的相邻事件时，「双删双插」会留下重复 job 行</li>
     * </ul>
     */
    @Transactional
    public void replaceJobs(WorkflowRun run, List<GithubJob> jobs) {
        Map<Long, RunJob> existing = new HashMap<>();
        for (RunJob job : jobMapper.selectList(new LambdaQueryWrapper<RunJob>()
                .eq(RunJob::getRunPk, run.getId()))) {
            if (job.getExternalJobId() != null) {
                existing.put(job.getExternalJobId(), job);
            }
        }

        Set<Long> seen = new HashSet<>();
        for (GithubJob payload : jobs) {
            if (payload.id() == null || !seen.add(payload.id())) {
                continue;
            }

            RunJob job = existing.remove(payload.id());
            boolean isNew = job == null;
            if (isNew) {
                job = new RunJob();
            }
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

            if (isNew) {
                jobMapper.insert(job);
            } else {
                jobMapper.updateById(job);
            }
            replaceSteps(job, payload.steps());
        }

        // GitHub 侧已经没有的 job（重跑换了 job、或矩阵缩小）才删
        for (RunJob stale : existing.values()) {
            stepMapper.delete(new LambdaQueryWrapper<RunStep>().eq(RunStep::getJobPk, stale.getId()));
            jobMapper.deleteById(stale.getId());
            log.info("job_removed run={} job={} name={}", run.getId(), stale.getId(), stale.getName());
        }
    }

    /** step 没有稳定的外部 id，按 job 整体替换即可（数量小、无外部引用）。 */
    private void replaceSteps(RunJob job, List<GithubStep> steps) {
        stepMapper.delete(new LambdaQueryWrapper<RunStep>().eq(RunStep::getJobPk, job.getId()));
        if (steps == null) {
            return;
        }
        for (GithubStep stepPayload : steps) {
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
