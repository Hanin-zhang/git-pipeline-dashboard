package com.githublook.settings;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.githublook.common.error.AppException;
import com.githublook.common.time.TimeMapper;
import com.githublook.settings.dto.NotificationDto;
import com.githublook.settings.dto.UpdateNotificationRequest;
import com.githublook.settings.mapper.NotificationSettingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final List<String> CHANNELS = List.of("DINGTALK", "FEISHU", "WEBHOOK");

    private final NotificationSettingMapper mapper;
    private final TimeMapper time;

    public List<NotificationDto> list() {
        ensureDefaults();
        return mapper.selectList(new LambdaQueryWrapper<NotificationSetting>()
                        .orderByAsc(NotificationSetting::getId))
                .stream().map(NotificationDto::from).toList();
    }

    @Transactional
    public NotificationDto update(String channel, UpdateNotificationRequest request) {
        String normalized = channel == null ? "" : channel.toUpperCase();
        if (!CHANNELS.contains(normalized)) {
            throw AppException.badRequest("不支持的通知渠道: " + channel);
        }
        ensureDefaults();
        NotificationSetting setting = mapper.selectOne(new LambdaQueryWrapper<NotificationSetting>()
                .eq(NotificationSetting::getChannel, normalized).last("LIMIT 1"));

        if (request.webhookUrl() != null) {
            setting.setWebhookUrl(request.webhookUrl().isBlank() ? null : request.webhookUrl().trim());
        }
        if (request.enabled() != null) {
            setting.setEnabled(request.enabled());
        }
        if (request.notifyOn() != null && !request.notifyOn().isBlank()) {
            setting.setNotifyOn(request.notifyOn());
        }
        if (Boolean.TRUE.equals(setting.getEnabled())
                && (setting.getWebhookUrl() == null || setting.getWebhookUrl().isBlank())) {
            throw AppException.badRequest("启用通知前请先填写 Webhook 地址");
        }
        setting.setUpdatedAt(time.now());
        mapper.updateById(setting);
        log.info("notification_updated channel={} enabled={}", normalized, setting.getEnabled());
        return NotificationDto.from(setting);
    }

    public List<NotificationSetting> activeChannels() {
        ensureDefaults();
        return mapper.selectList(new LambdaQueryWrapper<NotificationSetting>()
                .eq(NotificationSetting::getEnabled, true));
    }

    private void ensureDefaults() {
        Long existing = mapper.selectCount(null);
        if (existing != null && existing > 0) {
            return;
        }
        for (String channel : CHANNELS) {
            NotificationSetting setting = new NotificationSetting();
            setting.setChannel(channel);
            setting.setEnabled(false);
            setting.setNotifyOn("failure");
            setting.setUpdatedAt(time.now());
            mapper.insert(setting);
        }
    }
}
