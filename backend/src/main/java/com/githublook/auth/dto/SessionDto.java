package com.githublook.auth.dto;

public record SessionDto(String username, String displayName, String role, String expiresAt) {
}
