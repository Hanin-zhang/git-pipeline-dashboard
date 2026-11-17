package com.githublook.seed;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.githublook.common.config.AppProperties;
import com.githublook.common.time.TimeMapper;
import com.githublook.repo.MonitoredRepo;
import com.githublook.repo.mapper.MonitoredRepoMapper;
import com.githublook.run.RunWriteService;
import com.githublook.run.mapper.RunJobMapper;
import com.githublook.run.mapper.RunStepMapper;
import com.githublook.run.mapper.WorkflowRunMapper;
import com.githublook.webhook.payload.GithubCommit;
import com.githublook.webhook.payload.GithubCommitAuthor;
import com.githublook.webhook.payload.GithubJob;
import com.githublook.webhook.payload.GithubStep;
import com.githublook.webhook.payload.WorkflowRunData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seeds a realistic 7-day history so the dashboard has something to show on first start.
 * Generated through {@link RunWriteService}, i.e. exactly the same write path a real
 * webhook takes — no special-cased inserts.
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    private static final String[] AUTHORS = {"li.wei", "zhang.min", "chen.hao", "wang.fang", "octocat"};
    private static final String[] COMMITS = {
            "fix: 修复订单金额四舍五入精度问题",
            "feat: 新增优惠券叠加校验",
            "refactor: 拆分结算服务领域模型",
            "chore: 升级依赖到最新补丁版本",
            "perf: 减少列表接口 N+1 查询",
            "test: 补充支付回调幂等用例",
            "docs: 更新接入文档",
            "fix: 处理空购物车下单异常"
    };

    private final AppProperties props;
    private final TimeMapper time;
    private final MonitoredRepoMapper repoMapper;
    private final WorkflowRunMapper runMapper;
    private final RunJobMapper jobMapper;
    private final RunStepMapper stepMapper;
    private final RunWriteService runWriteService;

    private record RepoSeed(String fullName, String defaultBranch) {
    }

    private record WorkflowSeed(String name, String repo, String branch, int minSeconds, int maxSeconds,
                                double failureRate) {
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.getDemo().isEnabled()) {
            return;
        }
        if (props.getDemo().isReset()) {
            reset();
        }
        Long existing = runMapper.selectCount(new LambdaQueryWrapper<>());
        if (existing != null && existing > 0) {
            log.info("demo_seed_skipped existing_runs={}", existing);
            return;
        }

        seedRepos();
        int created = seedRuns();
        log.info("demo_seed_completed runs={} days={}", created, props.getDemo().getDays());
    }

    private void reset() {
        stepMapper.delete(new LambdaQueryWrapper<>());
        jobMapper.delete(new LambdaQueryWrapper<>());
        runMapper.delete(new LambdaQueryWrapper<>());
        log.info("demo_seed_reset done");
    }

    private void seedRepos() {
        List<RepoSeed> repos = List.of(
                new RepoSeed("pcxx/web-portal", "main"),
                new RepoSeed("pcxx/order-service", "main"),
                new RepoSeed("pcxx/data-sync", "develop"));
        for (RepoSeed seed : repos) {
            Long count = repoMapper.selectCount(new LambdaQueryWrapper<MonitoredRepo>()
                    .eq(MonitoredRepo::getFullName, seed.fullName()));
            if (count != null && count > 0) {
                continue;
            }
            MonitoredRepo repo = new MonitoredRepo();
            int slash = seed.fullName().indexOf('/');
            repo.setOwner(seed.fullName().substring(0, slash));
            repo.setName(seed.fullName().substring(slash + 1));
            repo.setFullName(seed.fullName());
            repo.setDefaultBranch(seed.defaultBranch());
            // Demo repos share the global secret so scripts/simulate_webhook.py works out of the box.
            repo.setWebhookSecret(props.getWebhook().getDefaultSecret());
            repo.setEnabled(true);
            repo.setCreatedAt(time.now());
            repoMapper.insert(repo);
        }
    }

    private int seedRuns() {
        List<WorkflowSeed> workflows = List.of(
                new WorkflowSeed("CI / Build & Test", "pcxx/web-portal", "main", 90, 260, 0.08),
                new WorkflowSeed("Deploy / Staging", "pcxx/web-portal", "main", 130, 300, 0.12),
                new WorkflowSeed("CI / Build & Test", "pcxx/order-service", "main", 120, 340, 0.10),
                new WorkflowSeed("Deploy / Production", "pcxx/order-service", "release/1.8", 200, 480, 0.16),
                new WorkflowSeed("Nightly / E2E", "pcxx/data-sync", "develop", 420, 900, 0.22),
                new WorkflowSeed("CI / Build & Test", "pcxx/data-sync", "feature/checkout", 80, 210, 0.18));

        Random random = new Random(20261117L);
        ZoneId zone = time.zone();
        LocalDate today = time.today();
        int days = Math.max(props.getDemo().getDays(), 1);
        int created = 0;

        for (int dayOffset = days - 1; dayOffset >= 0; dayOffset--) {
            LocalDate day = today.minusDays(dayOffset);
            boolean weekend = day.getDayOfWeek().getValue() >= 6;
            int runsToday = weekend ? 6 + random.nextInt(5) : 14 + random.nextInt(9);

            for (int i = 0; i < runsToday; i++) {
                WorkflowSeed workflow = workflows.get(random.nextInt(workflows.size()));
                int hour = 9 + random.nextInt(11);
                int minute = random.nextInt(60);
                LocalDateTime startedAt = LocalDateTime.of(day, LocalTime.of(hour, minute));
                if (startedAt.isAfter(time.now())) {
                    startedAt = time.now().minusMinutes(5 + random.nextInt(40));
                }

                boolean failed = random.nextDouble() < workflow.failureRate();
                int seconds = workflow.minSeconds() + random.nextInt(workflow.maxSeconds() - workflow.minSeconds());
                LocalDateTime completedAt = startedAt.plusSeconds(seconds);
                boolean stillRunning = dayOffset == 0 && i < 2;

                WorkflowRunData payload = buildPayload(workflow, random, startedAt, completedAt,
                        failed, stillRunning, zone);
                runWriteService.upsert(payload, workflow.repo(), "seed-" + day + "-" + i);
                created++;
            }
        }
        return created;
    }

    private WorkflowRunData buildPayload(WorkflowSeed workflow, Random random, LocalDateTime startedAt,
                                         LocalDateTime completedAt, boolean failed, boolean stillRunning,
                                         ZoneId zone) {
        String status = stillRunning ? "in_progress" : "completed";
        String conclusion = stillRunning ? null : (failed ? "failure" : "success");
        String sha = randomSha(random);
        String author = AUTHORS[random.nextInt(AUTHORS.length)];
        String message = COMMITS[random.nextInt(COMMITS.length)];

        List<GithubJob> jobs = buildJobs(workflow, random, startedAt, stillRunning, failed, zone);

        return new WorkflowRunData(
                (long) Math.abs(random.nextInt(900_000_000)) + 1_000,
                workflow.name(),
                workflow.branch(),
                sha,
                100 + random.nextInt(900),
                1,
                "push",
                status,
                conclusion,
                toInstant(startedAt, zone),
                toInstant(startedAt.minusSeconds(3), zone),
                toInstant(stillRunning ? startedAt.plusSeconds(60) : completedAt, zone),
                "https://github.com/" + workflow.repo() + "/actions/runs/" + Math.abs(random.nextInt(900_000_000)),
                null,
                new com.githublook.webhook.payload.GithubActor(author, "User"),
                new GithubCommit(sha, message, toInstant(startedAt, zone), new GithubCommitAuthor(author, null)),
                jobs);
    }

    private List<GithubJob> buildJobs(WorkflowSeed workflow, Random random, LocalDateTime startedAt,
                                      boolean stillRunning, boolean failed, ZoneId zone) {
        String[] jobNames = jobNamesFor(workflow.name());
        List<GithubJob> jobs = new ArrayList<>();
        LocalDateTime cursor = startedAt;
        int failedIndex = failed ? random.nextInt(jobNames.length) : -1;

        for (int i = 0; i < jobNames.length; i++) {
            boolean jobFailed = i == failedIndex;
            int jobSeconds = 20 + random.nextInt(120);
            LocalDateTime jobStart = cursor;
            LocalDateTime jobEnd = jobStart.plusSeconds(jobSeconds);
            boolean jobStillRunning = stillRunning && i == jobNames.length - 1;

            List<GithubStep> steps = buildSteps(jobNames[i], random, jobStart, jobStillRunning, jobFailed, zone);

            // 真实依赖：矩阵类 job（E2E）并行，其余串行依赖上一个 job
            List<String> needs = new ArrayList<>();
            if (i > 0 && !workflow.name().contains("E2E")) {
                needs.add(jobNames[i - 1]);
            }

            jobs.add(new GithubJob(
                    (long) Math.abs(random.nextInt(900_000_000)) + 1,
                    jobNames[i],
                    jobStillRunning ? "in_progress" : "completed",
                    jobStillRunning ? null : (jobFailed ? "failure" : "success"),
                    toInstant(jobStart, zone),
                    jobStillRunning ? null : toInstant(jobEnd, zone),
                    "GitHub Actions " + (random.nextBoolean() ? "4" : "8") + "-core",
                    "https://github.com/" + workflow.repo() + "/actions",
                    needs,
                    steps));
            cursor = jobEnd.plusSeconds(5);
        }
        return jobs;
    }

    private List<GithubStep> buildSteps(String jobName, Random random, LocalDateTime jobStart,
                                        boolean stillRunning, boolean failed, ZoneId zone) {
        List<String> names = new ArrayList<>(List.of("Set up job", "Checkout code"));
        if (jobName.contains("E2E")) {
            names.addAll(List.of("Setup Node", "Install dependencies", "Build", "Run E2E suite", "Upload report"));
        } else if (jobName.contains("Deploy")) {
            names.addAll(List.of("Configure cloud credentials", "Build image", "Push image", "Rollout"));
        } else {
            names.addAll(List.of("Setup JDK 21", "Cache maven", "Compile", "Unit tests", "Package"));
        }
        names.add("Complete job");

        int failAt = failed ? Math.max(2, names.size() - 3) : -1;
        List<GithubStep> steps = new ArrayList<>();
        LocalDateTime cursor = jobStart;
        for (int i = 0; i < names.size(); i++) {
            int seconds = 3 + random.nextInt(45);
            LocalDateTime stepEnd = cursor.plusSeconds(seconds);
            boolean stepFailed = i == failAt;
            boolean pending = stillRunning && i >= names.size() - 2;
            steps.add(new GithubStep(names.get(i), i + 1,
                    pending ? "pending" : (stepFailed ? "completed" : "completed"),
                    pending ? null : (stepFailed ? "failure" : "success"),
                    pending ? null : toInstant(cursor, zone),
                    pending ? null : toInstant(stepEnd, zone)));
            cursor = stepEnd;
        }
        return steps;
    }

    private String[] jobNamesFor(String workflowName) {
        if (workflowName.contains("E2E")) {
            return new String[]{"e2e (chromium)", "e2e (firefox)"};
        }
        if (workflowName.contains("Deploy")) {
            return new String[]{"build", "deploy"};
        }
        return new String[]{"lint", "test (jdk21)", "build"};
    }

    private String randomSha(Random random) {
        StringBuilder sb = new StringBuilder(40);
        String hex = "0123456789abcdef";
        for (int i = 0; i < 40; i++) {
            sb.append(hex.charAt(random.nextInt(16)));
        }
        return sb.toString();
    }

    private Instant toInstant(LocalDateTime value, ZoneId zone) {
        return value.atZone(zone).toInstant();
    }
}
