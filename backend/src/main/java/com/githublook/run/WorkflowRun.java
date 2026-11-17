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

    public boolean isFinished() {
        return "completed".equals(status) && conclusion != null;
    }

    public boolean isFailed() {
        return "failure".equals(conclusion) || "timed_out".equals(conclusion)
                || "startup_failure".equals(conclusion);
    }
}
