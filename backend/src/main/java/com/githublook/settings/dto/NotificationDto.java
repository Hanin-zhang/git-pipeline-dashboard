package com.githublook.settings.dto;

import com.githublook.settings.NotificationSetting;

import java.time.LocalDateTime;

public record NotificationDto(
        String channel,
        String label,
        String webhookUrl,
        boolean enabled,
        String notifyOn,
        LocalDateTime updatedAt) {

    public static String labelOf(String channel) {
        return switch (channel) {
            case "DINGTALK" -> "钉钉机器人";
            case "FEISHU" -> "飞书机器人";
            default -> "通用 Webhook";
        };
    }

    public static NotificationDto from(NotificationSetting setting) {
        String url = setting.getWebhookUrl();
        return new NotificationDto(
                setting.getChannel(),
                labelOf(setting.getChannel()),
                // Only the tail is returned so a stored secret cannot be scraped from the UI.
                url == null || url.isBlank() ? null : mask(url),
                Boolean.TRUE.equals(setting.getEnabled()),
                setting.getNotifyOn(),
                setting.getUpdatedAt());
    }

    public static String mask(String url) {
        if (url.length() <= 12) {
            return "****";
        }
        return url.substring(0, 8) + "****" + url.substring(url.length() - 4);
    }
}
