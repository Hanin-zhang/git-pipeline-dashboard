package com.githublook.run;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** A single job inside a workflow run (matrix entries show up as separate jobs). */
@Getter
@Setter
@NoArgsConstructor
@TableName("run_job")
public class RunJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runPk;
    private Long externalJobId;
    private String name;
    private String status;
    private String conclusion;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long durationMs;
    private String runnerName;
    private String htmlUrl;
    /** Comma separated job names this job depends on (GitHub Actions {@code needs}). */
    private String needs;
}
