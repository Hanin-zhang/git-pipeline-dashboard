package com.githublook.run;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Individual step of a job — this is what makes a red run explainable. */
@Getter
@Setter
@NoArgsConstructor
@TableName("run_step")
public class RunStep {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobPk;
    private Integer stepNumber;
    private String name;
    private String status;
    private String conclusion;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
