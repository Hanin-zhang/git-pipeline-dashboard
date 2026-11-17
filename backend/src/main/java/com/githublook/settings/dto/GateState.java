package com.githublook.settings.dto;

/**
 * 设置页门禁状态。
 * locked=true 时前端只渲染密码框；false 时展示完整设置内容。
 */
public record GateState(boolean locked, String expiresAt, int ttlMinutes) {
}
