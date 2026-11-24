package com.githublook.run;

import com.githublook.common.error.AppException;
import com.githublook.github.GithubApiClient;
import com.githublook.run.log.LogDigest;
import com.githublook.run.log.LogDigestCodec;
import com.githublook.run.mapper.RunJobMapper;
import com.githublook.run.mapper.WorkflowRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RunLogService · 三层读取顺序")
class RunLogServiceTest {

    @Mock
    private WorkflowRunMapper runMapper;
    @Mock
    private RunJobMapper jobMapper;
    @Mock
    private GithubApiClient github;
    @Mock
    private LogDigestCodec codec;
    @Mock
    private RunFailureClassifier classifier;

    private RunLogService service;

    private static final String REPO = "pcxx/web-portal";

    @BeforeEach
    void setUp() {
        service = new RunLogService(runMapper, jobMapper, github, codec, classifier);
    }

    private static WorkflowRun run() {
        WorkflowRun run = new WorkflowRun();
        run.setId(10L);
        run.setRepoFullName(REPO);
        run.setFailureJobName("Python · pytest");
        return run;
    }

    private static RunJob job() {
        RunJob job = new RunJob();
        job.setId(20L);
        job.setRunPk(10L);
        job.setExternalJobId(7001L);
        job.setName("Python · pytest");
        job.setConclusion("failure");
        return job;
    }

    private static LogDigest digest() {
        return new LogDigest("failure", "test", "2 处测试失败",
                new LogDigest.Stats(100, 5, 80, 2), List.of());
    }

    @Test
    @DisplayName("库里已有摘要时直接返回，完全不打 GitHub")
    void hitsDatabaseFirst() {
        when(runMapper.selectById(10L)).thenReturn(run());
        when(jobMapper.selectById(20L)).thenReturn(job());
        when(codec.decode(any(), any())).thenReturn(Optional.of(digest()));

        LogDigest result = service.jobLogSummary(10L, 20L);

        assertThat(result.headline()).isEqualTo("2 处测试失败");
        verify(github, never()).fetchJobLogs(anyString(), anyLong());
        verify(classifier, never()).saveAsync(any(), any(), any());
    }

    @Test
    @DisplayName("库里没有时回源 GitHub，并把结果异步写回")
    void fallsBackToGithubAndSaves() {
        when(runMapper.selectById(10L)).thenReturn(run());
        when(jobMapper.selectById(20L)).thenReturn(job());
        when(codec.decode(any(), any())).thenReturn(Optional.empty());
        when(github.fetchJobLogs(REPO, 7001L)).thenReturn("""
                FAILED tests/test_a.py::test_x - AssertionError
                ##[error]Process completed with exit code 1
                """);

        LogDigest result = service.jobLogSummary(10L, 20L);

        assertThat(result.verdict()).isEqualTo("failure");
        assertThat(result.failureClass()).isEqualTo("test");

        ArgumentCaptor<LogDigest> saved = ArgumentCaptor.forClass(LogDigest.class);
        verify(classifier).saveAsync(any(WorkflowRun.class), eq("Python · pytest"), saved.capture());
        assertThat(saved.getValue().headline()).contains("测试失败");
    }

    @Test
    @DisplayName("同一 job 连续请求只打一次 GitHub，第二次命中进程内缓存")
    void usesInProcessCache() {
        when(runMapper.selectById(10L)).thenReturn(run());
        when(jobMapper.selectById(20L)).thenReturn(job());
        when(codec.decode(any(), any())).thenReturn(Optional.empty());
        when(github.fetchJobLogs(REPO, 7001L)).thenReturn("##[error]boom");

        service.jobLogs(10L, 20L);
        service.jobLogs(10L, 20L);

        verify(github, org.mockito.Mockito.times(1)).fetchJobLogs(REPO, 7001L);
    }

    @Test
    @DisplayName("打开非失败 job 时不复用失败 job 的摘要，而是回源取它自己的日志")
    void doesNotReuseDigestForOtherJobs() {
        WorkflowRun run = run();
        run.setFailureDigest("{\"verdict\":\"failure\"}");
        run.setFailureDigestVersion(3);
        when(runMapper.selectById(10L)).thenReturn(run);

        RunJob web = job();
        web.setName("Web · 类型检查与单元测试");
        web.setConclusion("success");
        when(jobMapper.selectById(20L)).thenReturn(web);
        when(github.fetchJobLogs(REPO, 7001L)).thenReturn("all checks passed");

        LogDigest result = service.jobLogSummary(10L, 20L);

        assertThat(result.verdict()).isEqualTo("success");
        verify(codec, never()).decode(any(), any());
        verify(classifier, never()).saveAsync(any(), any(), any());
    }

    @Test
    @DisplayName("job 不属于该运行时按未找到处理")
    void rejectsForeignJob() {
        when(runMapper.selectById(10L)).thenReturn(run());
        RunJob other = job();
        other.setRunPk(99L);
        when(jobMapper.selectById(20L)).thenReturn(other);

        assertThatThrownBy(() -> service.jobLogSummary(10L, 20L))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("运行不存在时按未找到处理")
    void rejectsMissingRun() {
        when(runMapper.selectById(10L)).thenReturn(null);

        assertThatThrownBy(() -> service.jobLogs(10L, 20L))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("job 缺少 GitHub Job ID 时给出可操作的提示")
    void rejectsJobWithoutExternalId() {
        when(runMapper.selectById(10L)).thenReturn(run());
        RunJob withoutId = job();
        withoutId.setExternalJobId(null);
        when(jobMapper.selectById(20L)).thenReturn(withoutId);

        assertThatThrownBy(() -> service.jobLogs(10L, 20L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("同步 Job 明细");
    }
}
