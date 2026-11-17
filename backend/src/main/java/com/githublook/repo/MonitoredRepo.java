package com.githublook.repo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** A repository whose GitHub Actions events we index. */
@Getter
@Setter
@NoArgsConstructor
@TableName("monitored_repo")
public class MonitoredRepo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String owner;
    private String name;
    private String fullName;
    private String defaultBranch;
    /** Per-repo webhook secret; takes precedence over the global fallback secret. */
    private String webhookSecret;
    private Boolean enabled;
    private LocalDateTime createdAt;
}
