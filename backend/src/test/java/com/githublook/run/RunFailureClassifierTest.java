package com.githublook.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.githublook.github.GithubApiClient;
import com.githublook.run.log.LogDigestCodec;
import com.githublook.run.mapper.RunJobMapper;
import com.githublook.support.MybatisLambdaTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RunFailureClassifier · 失败原因自动归类")
class RunFailureClassifierTest {

    @Mock
    private GithubApiClient github;

    @Mock
    private RunJobMapper jobMapper;

    @Mock
    private RunWriteService runWriteService;

    @Mock
    private LogDigestCodec codec;

    private RunFailureClassifier classifier;

    private static final String REPO = "pcxx/web-portal";

    @BeforeAll
    static void initLambdaCache() {
        MybatisLambdaTestSupport.init();
    }

    @BeforeEach
    void setUp() {
        classifier = new RunFailureClassifier(github, jobMapper, runWriteService, codec);
    }

    private static WorkflowRun run(Long id, String conclusion) {
        WorkflowRun run = new WorkflowRun();
        run.setId(id);
        run.setExternalRunId(900L + id);
        run.setRepoFullName(REPO);
        run.setConclusion(conclusion);
        run.setStatus("completed");
        return run;
    }

    private static RunJob job(Long externalJobId, String conclusion) {
        RunJob job = new RunJob();
        job.setId(1L);
        job.setRunPk(1L);
        job.setExternalJobId(externalJobId);
        job.setName("Python · pytest");
        job.setConclusion(conclusion);
        return job;
    }

    @Test
    @DisplayName("没配 Token 时直接返回，不触发任何下载")
    void skipsWhenTokenMissing() {
        when(github.isConfigured()).thenReturn(false);

        classifier.classifyAsync(run(1L, "failure"));

        verify(jobMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verify(github, never()).fetchJobLogs(anyString(), anyLong());
    }

    @Test
    @DisplayName("已归类过的运行不再重复下载日志")
    void skipsAlreadyClassified() {
        WorkflowRun run = run(2L, "failure");
        run.setFailureClass("test");

        String result = classifier.classify(run);

        assertThat(result).isEqualTo("test");
        verify(github, never()).fetchJobLogs(anyString(), anyLong());
        verify(runWriteService, never()).updateFailureClassification(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("测试失败 → 归类为 test 并写库")
    void classifiesTestFailure() {
        when(jobMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(job(7001L, "failure")));
        when(github.fetchJobLogs(REPO, 7001L)).thenReturn("""
                FAILED tests/test_order.py::test_create - AssertionError
                E   assert 400 == 200
                ##[error]Process completed with exit code 1
                """);

        String result = classifier.classify(run(3L, "failure"));

        assertThat(result).isEqualTo("test");

        org.mockito.ArgumentCaptor<String> jobName = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> summary = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(runWriteService).updateFailureClassification(
                eq(3L), eq("test"), jobName.capture(), summary.capture(), any(), any());

        assertThat(jobName.getValue()).isEqualTo("Python · pytest");
        assertThat(summary.getValue()).contains("测试失败");
        assertThat(summary.getValue()).contains("tests/test_order.py");
    }

    @Test
    @DisplayName("依赖问题 → 归类为 dependency")
    void classifiesDependencyFailure() {
        when(jobMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(job(7002L, "failure")));
        when(github.fetchJobLogs(REPO, 7002L)).thenReturn("npm error code E404\nnpm error 404 Not Found");

        assertThat(classifier.classify(run(4L, "failure"))).isEqualTo("dependency");
        verify(runWriteService).updateFailureClassification(eq(4L), eq("dependency"), any(), any(), any(), any());
    }

    @Test
    @DisplayName("日志里认不出具体原因时，写 unknown 表示「已尝试过」，避免反复下载")
    void writesUnknownWhenUnrecognised() {
        when(jobMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(job(7003L, "failure")));
        when(github.fetchJobLogs(REPO, 7003L)).thenReturn("##[error]Something went sideways with code 9");

        assertThat(classifier.classify(run(5L, "failure"))).isEqualTo("unknown");
        verify(runWriteService).updateFailureClassification(eq(5L), eq("unknown"), any(), any(), any(), any());
    }

    @Test
    @DisplayName("日志看起来没有错误时不写库，避免污染统计")
    void doesNotWriteWhenLogLooksClean() {
        when(jobMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(job(7004L, "failure")));
        when(github.fetchJobLogs(REPO, 7004L)).thenReturn("collected 12 items\n12 passed in 1.2s");

        assertThat(classifier.classify(run(6L, "failure"))).isNull();
        verify(runWriteService, never()).updateFailureClassification(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("没有失败的 job 时不做任何事")
    void skipsWhenNoFailedJob() {
        when(jobMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(job(7005L, "success"), job(7006L, "skipped")));

        assertThat(classifier.classify(run(7L, "failure"))).isNull();
        verify(github, never()).fetchJobLogs(anyString(), anyLong());
    }

    @Test
    @DisplayName("job 缺少 GitHub Job ID 时跳过（多见于没同步过明细的老数据）")
    void skipsWhenJobIdMissing() {
        when(jobMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(job(null, "failure")));

        assertThat(classifier.classify(run(8L, "failure"))).isNull();
        verify(github, never()).fetchJobLogs(anyString(), anyLong());
    }

    @Test
    @DisplayName("下载抛异常时吞掉，不把错误抛给调用方")
    void swallowsDownloadErrors() {
        when(jobMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(job(7007L, "failure")));
        when(github.fetchJobLogs(REPO, 7007L)).thenThrow(new IllegalStateException("boom"));

        assertThat(classifier.classify(run(9L, "failure"))).isNull();
        verify(runWriteService, never()).updateFailureClassification(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("timed_out 也算失败，会去归类")
    void treatsTimedOutAsFailure() {
        when(jobMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(job(7008L, "timed_out")));
        when(github.fetchJobLogs(REPO, 7008L))
                .thenReturn("The job running on runner X has exceeded the maximum execution time of 360 minutes.");

        assertThat(classifier.classify(run(10L, "timed_out"))).isEqualTo("timeout");
    }
}
