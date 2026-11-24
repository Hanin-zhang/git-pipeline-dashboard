package com.githublook.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.githublook.common.error.AppException;
import com.githublook.common.error.ErrorCode;
import com.githublook.github.GithubApiClient;
import com.githublook.run.mapper.WorkflowRunMapper;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DigestBackfillService · 历史摘要回刷")
class DigestBackfillServiceTest {

    @Mock
    private WorkflowRunMapper runMapper;
    @Mock
    private RunFailureClassifier classifier;
    @Mock
    private GithubApiClient github;

    private DigestBackfillService service;

    @BeforeAll
    static void initLambdaCache() {
        MybatisLambdaTestSupport.init();
    }

    @BeforeEach
    void setUp() {
        service = new DigestBackfillService(runMapper, classifier, github);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "defaultLimit", 50);
    }

    private static WorkflowRun run(long id) {
        WorkflowRun run = new WorkflowRun();
        run.setId(id);
        run.setRepoFullName("acme/app");
        run.setConclusion("failure");
        return run;
    }

    @Test
    @DisplayName("没配 Token 时直接跳过，不查库")
    void skipsWithoutToken() {
        when(github.isConfigured()).thenReturn(false);

        DigestBackfillService.Result result = service.run(10);

        assertThat(result.skipped()).isTrue();
        assertThat(result.reason()).contains("GITHUB_TOKEN");
        verify(runMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("没有待回刷的记录时正常返回")
    void emptyCandidates() {
        when(github.isConfigured()).thenReturn(true);
        when(runMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        DigestBackfillService.Result result = service.run(10);

        assertThat(result.skipped()).isFalse();
        assertThat(result.candidates()).isZero();
        assertThat(result.reason()).contains("没有需要回刷");
    }

    @Test
    @DisplayName("逐条强制重算，统计更新条数")
    void classifiesEachCandidate() {
        when(github.isConfigured()).thenReturn(true);
        when(runMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(run(1), run(2)));
        when(classifier.classifyOrThrow(any(WorkflowRun.class), eq(true)))
                .thenReturn("test", "dependency");

        DigestBackfillService.Result result = service.run(10);

        assertThat(result.candidates()).isEqualTo(2);
        assertThat(result.updated()).isEqualTo(2);
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("鉴权失败时立即中止整轮，而不是继续消耗配额")
    void abortsOnAuthFailure() {
        when(github.isConfigured()).thenReturn(true);
        when(runMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(run(1), run(2), run(3)));
        when(classifier.classifyOrThrow(any(WorkflowRun.class), anyBoolean()))
                .thenThrow(new AppException(ErrorCode.SERVICE_UNAVAILABLE,
                        "github 401 Bad credentials"));

        DigestBackfillService.Result result = service.run(10);

        assertThat(result.reason()).contains("鉴权失败");
        assertThat(result.updated()).isZero();
        // 只处理了一条就中止
        verify(classifier).classifyOrThrow(any(WorkflowRun.class), eq(true));
    }

    @Test
    @DisplayName("连续多条拿不到结果时熔断，避免空转")
    void breaksOnConsecutiveFailures() {
        when(github.isConfigured()).thenReturn(true);
        when(runMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(run(1), run(2), run(3), run(4)));
        when(classifier.classifyOrThrow(any(WorkflowRun.class), eq(true))).thenReturn(null);

        DigestBackfillService.Result result = service.run(10);

        assertThat(result.failed()).isEqualTo(3);
        assertThat(result.reason()).contains("连续");
        // 第 4 条不该再被处理
        verify(classifier, org.mockito.Mockito.times(3)).classifyOrThrow(any(WorkflowRun.class), eq(true));
    }
}
