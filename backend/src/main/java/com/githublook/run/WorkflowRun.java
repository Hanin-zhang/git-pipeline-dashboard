package com.githublook.run;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** One GitHub Actions workflow run, normalised from the workflow_run webhook payload. */
@Getter
@Setter
@NoArgsConstructor
@TableName("workflow_run")
public class WorkflowRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long externalRunId;
    private String repoFullName;
    private String workflowName;
    private Integer runNumber;
    private String branch;
    private String eventName;
    private String status;
    private String conclusion;
    private String actor;
    private String commitSha;
    private String commitMessage;
    private String commitAuthor;
    private String htmlUrl;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long durationMs;
    private String deliveryId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 失败原因归类：test / build / dependency / timeout / permission / network / unknown。未归类为 null。 */
    private String failureClass;

    /** 第一个失败的 job 名称，摘要就是从这个 job 的日志里得出来的 */
    private String failureJobName;

    /** 一句话结论，例如「2 处测试失败，集中在 tests/test_order.py」 */
    private String failureSummary;

    /** 精简版摘要 JSON（去掉了 context），供详情页与列表快速渲染 */
    private String failureDigest;

    /** 生成 failureDigest 时的解析规则版本，不匹配则视为未命中 */
    private Integer failureDigestVersion;

    public boolean isFinished() {
        return "completed".equals(status) && conclusion != null;
    }

    public boolean isFailed() {
        return "failure".equals(conclusion) || "timed_out".equals(conclusion)
                || "startup_failure".equals(conclusion);
    }
}
