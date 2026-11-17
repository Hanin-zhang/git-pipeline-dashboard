package com.githublook.settings.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateNotificationRequest(
        @Size(max = 500)
        @Pattern(regexp = "^$|^https?://.+", message = "Webhook 地址必须以 http(s):// 开头")
        String webhookUrl,
        Boolean enabled,
        @Pattern(regexp = "^$|failure|failure_and_recovery|all", message = "notifyOn 取值无效")
        String notifyOn) {
}
