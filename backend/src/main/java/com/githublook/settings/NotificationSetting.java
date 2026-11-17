package com.githublook.settings;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Outbound alerting configuration (DingTalk / Feishu / generic webhook). */
@Getter
@Setter
@NoArgsConstructor
@TableName("notification_setting")
public class NotificationSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** DINGTALK | FEISHU | WEBHOOK */
    private String channel;
    private String webhookUrl;
    private Boolean enabled;
    /** failure | failure_and_recovery | all */
    private String notifyOn;
    private LocalDateTime updatedAt;
}
