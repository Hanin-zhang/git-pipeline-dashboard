package com.githublook.run.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.githublook.run.WorkflowRun;
import com.githublook.stats.model.AggregateRow;
import com.githublook.stats.model.DailyStatRow;
import com.githublook.stats.model.FailureReasonRow;
import com.githublook.stats.model.WorkflowStatRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WorkflowRunMapper extends BaseMapper<WorkflowRun> {

    AggregateRow aggregate(@Param("from") LocalDateTime from,
                           @Param("to") LocalDateTime to,
                           @Param("repo") String repo,
                           @Param("branch") String branch);

    List<DailyStatRow> dailyStats(@Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to,
                                  @Param("repo") String repo,
                                  @Param("branch") String branch);

    List<WorkflowStatRow> workflowStats(@Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to,
                                        @Param("repo") String repo,
                                        @Param("branch") String branch,
                                        @Param("limit") int limit);

    /** 按 failure_class 聚合窗口内的失败运行；未归类的行 failure_class 为 null */
    List<FailureReasonRow> failureReasons(@Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to,
                                          @Param("repo") String repo,
                                          @Param("branch") String branch);

    List<String> distinctBranches(@Param("limit") int limit);
}
