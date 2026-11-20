package com.githublook.run;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RunActionService · 重跑 / 取消的状态守卫")
class RunActionServiceTest {

    @Mock
    private WorkflowRunMapper runMapper;
    @Mock
    private GithubApiClient github;

    private RunActionService service;

    @BeforeAll
    static void initMybatisLambdas() {
        MybatisLambdaTestSupport.init(WorkflowRun.class);
    }

    @BeforeEach
    void setUp() {
        service = new RunActionService(runMapper, github);
    }

    private WorkflowRun run(String status, String conclusion) {
        WorkflowRun run = new WorkflowRun();
        run.setId(1L);
        run.setExternalRunId(999L);
        run.setRepoFullName("acme/app");
        run.setStatus(status);
        run.setConclusion(conclusion);
        run.setHtmlUrl("https://github.com/acme/app/actions/runs/999");
        return run;
    }

    @Test
    @DisplayName("重跑：已结束的运行才能重跑")
    void rerunFinishedRun() {
        when(runMapper.selectById(1L)).thenReturn(run("completed", "failure"));

        RunActionService.ActionResult result = service.rerun(1L);

        verify(github).rerun("acme/app", 999L);
        assertThat(result.action()).isEqualTo("rerun");
        assertThat(result.runId()).isEqualTo(1L);
        assertThat(result.githubRunUrl()).contains("runs/999");
        assertThat(result.message()).contains("重新运行");
    }

    @Test
    @DisplayName("重跑：运行中直接 409，不向 GitHub 发请求")
    void refusesRerunningActiveRun() {
        when(runMapper.selectById(1L)).thenReturn(run("in_progress", null));

        assertThatThrownBy(() -> service.rerun(1L))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(github, never()).rerun(any(), anyLong());
    }

    @Test
    @DisplayName("取消：只有未结束的运行可以取消")
    void cancelsActiveRun() {
        when(runMapper.selectById(1L)).thenReturn(run("in_progress", null));

        RunActionService.ActionResult result = service.cancel(1L);

        verify(github).cancel("acme/app", 999L);
        assertThat(result.action()).isEqualTo("cancel");
    }

    @Test
    @DisplayName("取消：已结束直接 409")
    void refusesCancellingFinishedRun() {
        when(runMapper.selectById(1L)).thenReturn(run("completed", "success"));

        assertThatThrownBy(() -> service.cancel(1L))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(github, never()).cancel(any(), anyLong());
    }

    @Test
    @DisplayName("运行不存在 → 404")
    void reportsMissingRun() {
        when(runMapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.rerun(1L))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("GitHub 调用失败时异常直接上抛，本地状态不做乐观修改")
    void doesNotMutateLocalStateOnFailure() {
        when(runMapper.selectById(1L)).thenReturn(run("completed", "failure"));
        doThrow(new AppException(ErrorCode.FORBIDDEN, "权限不足"))
                .when(github).rerun(any(), anyLong());

        assertThatThrownBy(() -> service.rerun(1L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("权限不足");
        verify(runMapper, never()).updateById(any(WorkflowRun.class));
    }

    @Test
    @DisplayName("status=completed 但 conclusion 为空时视为未结束（GitHub 尚未回填结论）")
    void treatsCompletedWithoutConclusionAsUnfinished() {
        when(runMapper.selectById(1L)).thenReturn(run("completed", null));

        assertThatThrownBy(() -> service.rerun(1L)).isInstanceOf(AppException.class);
    }
}
