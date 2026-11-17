package com.githublook.settings.dto;

import com.githublook.repo.dto.RepoDto;

import java.util.List;
import java.util.Map;

/** Everything the Settings page needs in one round trip. */
public record SettingsDto(
        String webhookPath,
        String webhookUrl,
        String secretHint,
        List<RepoDto> repos,
        List<NotificationDto> notifications,
        RuntimeInfo runtime) {

    public record RuntimeInfo(
            String serverTime,
            String zone,
            long queueDepth,
            int consumers,
            long processed,
            long failed,
            Map<String, Long> deliveries,
            int liveConnections) {
    }
}
