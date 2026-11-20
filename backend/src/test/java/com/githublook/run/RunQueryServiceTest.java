package com.githublook.run;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.githublook.common.error.AppException;
import com.githublook.common.error.ErrorCode;
import com.githublook.common.web.PageResponse;
import com.githublook.repo.RepoService;
import com.githublook.run.dto.RunDetailDto;
import com.githublook.run.dto.RunFiltersDto;
import com.githublook.run.dto.RunQuery;
import com.githublook.run.dto.RunSummaryDto;
import com.githublook.run.mapper.RunJobMapper;
import com.githublook.run.mapper.RunStepMapper;
import com.githublook.run.mapper.WorkflowRunMapper;
import com.githublook.support.MybatisLambdaTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RunQueryService · 列表筛选与详情")
class RunQueryServiceTest {

    @Mock
    private WorkflowRunMapper runMapper;
    @Mock
    private RunJobMapper jobMapper;
    @Mock
    private RunStepMapper stepMapper;
    @Mock
    private RepoService repoService;

    private RunQueryService service;

    @BeforeAll
    static void initMybatisLambdas() {
        MybatisLambdaTestSupport.init(WorkflowRun.class, RunJob.class, RunStep.class);
    }

    @BeforeEach
    void setUp() {
        service = new RunQueryService(runMapper, jobMapper, stepMapper, repoService);
        when(jobMapper.selectList(any())).thenReturn(List.of());
        when(stepMapper.selectList(any())).thenReturn(List.of());
        when(runMapper.distinctBranches(any(Integer.class))).thenReturn(List.of("main", "develop"));
    }

    private RunQuery query(String status) {
        return new RunQuery(1, 20, status, null, null, null, null, null, null);
    }

    private WorkflowRun run(long id, String status, String conclusion) {
        WorkflowRun run = new WorkflowRun();
        run.setId(id);
        run.setExternalRunId(id * 10);
        run.setRepoFullName("acme/app");
        run.setWorkflowName("CI");
        run.setBranch("main");
        run.setStatus(status);
        run.setConclusion(conclusion);
        run.setStartedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
        return run;
    }

    private void pageReturns(List<WorkflowRun> records, long total) {
        Page<WorkflowRun> page = new Page<>(1, 20);
        page.setRecords(records);
        page.setTotal(total);
        when(runMapper.selectPage(any(), any())).thenReturn(page);
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<WorkflowRun> captureWrapper() {
        ArgumentCaptor<Wrapper<WorkflowRun>> captor = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(runMapper).selectPage(any(), captor.capture());
        return (LambdaQueryWrapper<WorkflowRun>) captor.getValue();
    }

    /**
     * MyBatis-Plus 的条件值是惰性写入 paramNameValuePairs 的：只有调用 getSqlSegment()
     * 触发 SQL 片段生成时才会填充。所以断言前必须先「渲染」一次。
     */
    private Collection<Object> params(LambdaQueryWrapper<WorkflowRun> wrapper) {
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs().values();
    }

    @Nested
    @DisplayName("状态分组")
    class StatusGroups {

        @Test
        @DisplayName("success 只筛 conclusion=success")
        void filtersSuccess() {
            pageReturns(List.of(run(1, "completed", "success")), 1);

            service.search(query("success"));

            assertThat(params(captureWrapper())).containsExactly("success");
        }

        @Test
        @DisplayName("failure 覆盖 failure / timed_out / startup_failure 三种失败")
        void filtersFailure() {
            pageReturns(List.of(), 0);

            service.search(query("failure"));

            assertThat(params(captureWrapper())).contains("failure", "timed_out", "startup_failure");
        }

        @Test
        @DisplayName("cancelled 覆盖 cancelled / skipped / stale")
        void filtersCancelled() {
            pageReturns(List.of(), 0);

            service.search(query("cancelled"));

            assertThat(params(captureWrapper())).contains("cancelled", "skipped", "stale");
        }

        @Test
        @DisplayName("running 按 status 而不是 conclusion 判断")
        void filtersRunning() {
            pageReturns(List.of(), 0);

            service.search(query("running"));

            assertThat(params(captureWrapper()))
                    .contains("queued", "in_progress", "requested", "waiting", "pending");
        }

        @Test
        @DisplayName("非法状态值退化成 all，不加任何条件（不会被注入成 SQL）")
        void unknownStatusFallsBackToAll() {
            pageReturns(List.of(run(1, "completed", "success")), 1);

            service.search(query("'; DROP TABLE workflow_run; --"));

            assertThat(params(captureWrapper())).isEmpty();
        }
    }

    @Nested
    @DisplayName("其它筛选条件")
    class OtherFilters {

        @Test
        @DisplayName("repo / branch / workflow 精确匹配")
        void filtersExactFields() {
            pageReturns(List.of(), 0);

            service.search(new RunQuery(1, 20, "all", "acme/app", "main", null, null, null, "CI"));

            assertThat(params(captureWrapper())).contains("acme/app", "main", "CI");
        }

        @Test
        @DisplayName("关键字同时模糊匹配 5 个字段（workflow / commit / actor / branch / sha）")
        void filtersKeywordAcrossFields() {
            pageReturns(List.of(), 0);

            service.search(new RunQuery(1, 20, "all", null, null, "flaky", null, null, null));

            // MyBatis-Plus 的 like 会自动加通配符，5 个字段各出现一次
            assertThat(params(captureWrapper())).containsOnly("%flaky%").hasSize(5);
        }

        @Test
        @DisplayName("日期区间：from 含当天 00:00，to 含当天 23:59:59（用次日 00:00 做开区间）")
        void filtersDateRange() {
            pageReturns(List.of(), 0);

            service.search(new RunQuery(1, 20, "all", null, null, null,
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 7), null));

            assertThat(params(captureWrapper())).contains(
                    LocalDateTime.of(2026, 1, 1, 0, 0),
                    LocalDateTime.of(2026, 1, 8, 0, 0));
        }
    }

    @Nested
    @DisplayName("分页与批量装配")
    class Paging {

        @Test
        @DisplayName("返回统一分页信封，totalPages 向上取整")
        void returnsPageEnvelope() {
            pageReturns(List.of(run(1, "completed", "success")), 105);

            PageResponse<RunSummaryDto> response = service.search(query("all"));

            assertThat(response.total()).isEqualTo(105);
            assertThat(response.totalPages()).isEqualTo(6);
            assertThat(response.items()).hasSize(1);
            assertThat(response.items().get(0).repoFullName()).isEqualTo("acme/app");
        }

        @Test
        @DisplayName("整页 job 只查一次（无 N+1）")
        void loadsStagesInOneQuery() {
            pageReturns(List.of(run(1, "completed", "success"), run(2, "completed", "failure")), 2);
            RunJob jobA = new RunJob();
            jobA.setId(10L);
            jobA.setRunPk(1L);
            jobA.setName("build");
            jobA.setStatus("completed");
            jobA.setConclusion("success");
            jobA.setDurationMs(1000L);
            RunJob jobB = new RunJob();
            jobB.setId(11L);
            jobB.setRunPk(2L);
            jobB.setName("test");
            when(jobMapper.selectList(any())).thenReturn(List.of(jobA, jobB));

            PageResponse<RunSummaryDto> response = service.search(query("all"));

            verify(jobMapper, times(1)).selectList(any());
            assertThat(response.items().get(0).stages()).hasSize(1);
            assertThat(response.items().get(0).stages().get(0).name()).isEqualTo("build");
            assertThat(response.items().get(1).stages()).hasSize(1);
            assertThat(response.items().get(1).stages().get(0).name()).isEqualTo("test");
        }

        @Test
        @DisplayName("空页不查 job（少一次无意义查询）")
        void skipsJobQueryForEmptyPage() {
            pageReturns(List.of(), 0);

            PageResponse<RunSummaryDto> response = service.search(query("all"));

            assertThat(response.items()).isEmpty();
            verify(jobMapper, org.mockito.Mockito.never()).selectList(any());
        }

        @Test
        @DisplayName("某条运行没有 job 时 stages 为空数组而不是 null（前端可直接渲染）")
        void missingStagesBecomeEmptyList() {
            pageReturns(List.of(run(1, "queued", null)), 1);

            assertThat(service.search(query("all")).items().get(0).stages()).isEmpty();
        }
    }

    @Nested
    @DisplayName("详情")
    class Detail {

        @Test
        @DisplayName("不存在返回 404")
        void notFound() {
            when(runMapper.selectById(1L)).thenReturn(null);

            assertThatThrownBy(() -> service.detail(1L))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("job 与 step 按 id / step_number 排序装配")
        void assemblesJobsAndSteps() {
            when(runMapper.selectById(1L)).thenReturn(run(1, "completed", "success"));
            RunJob job = new RunJob();
            job.setId(10L);
            job.setRunPk(1L);
            job.setName("build");
            job.setNeeds("lint,setup");
            when(jobMapper.selectList(any())).thenReturn(List.of(job));
            RunStep step = new RunStep();
            step.setId(20L);
            step.setJobPk(10L);
            step.setStepNumber(1);
            step.setName("checkout");
            when(stepMapper.selectList(any())).thenReturn(List.of(step));

            RunDetailDto detail = service.detail(1L);

            assertThat(detail.id()).isEqualTo(1L);
            assertThat(detail.jobs()).hasSize(1);
            assertThat(detail.jobs().get(0).needs()).containsExactly("lint", "setup");
            assertThat(detail.jobs().get(0).steps()).hasSize(1);
            assertThat(detail.jobs().get(0).steps().get(0).name()).isEqualTo("checkout");
        }

        @Test
        @DisplayName("没有 job 时不发起 step 查询")
        void skipsStepQueryWhenNoJobs() {
            when(runMapper.selectById(1L)).thenReturn(run(1, "queued", null));

            RunDetailDto detail = service.detail(1L);

            assertThat(detail.jobs()).isEmpty();
            verify(stepMapper, org.mockito.Mockito.never()).selectList(any());
        }

        @Test
        @DisplayName("needs 三态：null 表示未知（前端降级按执行顺序）、空串表示根节点")
        void parsesNeedsTriState() {
            when(runMapper.selectById(1L)).thenReturn(run(1, "completed", "success"));
            RunJob unknown = new RunJob();
            unknown.setId(1L);
            unknown.setRunPk(1L);
            unknown.setNeeds(null);
            RunJob root = new RunJob();
            root.setId(2L);
            root.setRunPk(1L);
            root.setNeeds("");
            RunJob blank = new RunJob();
            blank.setId(3L);
            blank.setRunPk(1L);
            blank.setNeeds(" , ");
            when(jobMapper.selectList(any())).thenReturn(List.of(unknown, root, blank));

            List<RunDetailDto.JobDto> jobs = service.detail(1L).jobs();

            assertThat(jobs.get(0).needs()).isNull();
            assertThat(jobs.get(1).needs()).isEmpty();
            assertThat(jobs.get(2).needs()).isEmpty();
        }
    }

    @Nested
    @DisplayName("筛选候选项")
    class Filters {

        @Test
        @DisplayName("仓库来自启用列表、分支来自真实数据、状态固定 5 项")
        void buildsFilterOptions() {
            when(repoService.activeFullNames()).thenReturn(List.of("acme/app", "acme/api"));

            RunFiltersDto filters = service.filters();

            assertThat(filters.repos()).containsExactly("acme/app", "acme/api");
            assertThat(filters.branches()).containsExactly("main", "develop");
            assertThat(filters.statuses()).extracting(RunFiltersDto.FilterOption::value)
                    .containsExactly("all", "success", "failure", "running", "cancelled");
            assertThat(filters.statuses()).allSatisfy(o -> assertThat(o.label()).isNotBlank());
        }

        @Test
        @DisplayName("分支候选最多 50 个，避免下拉框过长")
        void capsBranchOptions() {
            service.branchOptions();

            verify(runMapper).distinctBranches(50);
        }
    }
}
