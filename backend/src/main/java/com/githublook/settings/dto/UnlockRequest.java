package com.githublook.settings.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 解锁「设置与接入」页所需的口令 */
public record UnlockRequest(
        @NotBlank(message = "请输入访问密码")
        @Size(max = 200)
        String password) {
}
