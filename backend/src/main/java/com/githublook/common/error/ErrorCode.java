package com.githublook.common.error;

import org.springframework.http.HttpStatus;

/**
 * Every failure surfaced to an API client is one of these codes.
 * The client only ever sees the code + a human message, never a stack trace.
 */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "请求无效"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "未登录或登录已过期"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "没有访问权限"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    CONFLICT(HttpStatus.CONFLICT, "资源冲突"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "请求体超过大小限制"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持的 Content-Type"),
    SIGNATURE_INVALID(HttpStatus.UNAUTHORIZED, "Webhook 签名校验失败"),
    REPO_NOT_CONFIGURED(HttpStatus.NOT_FOUND, "仓库未接入或已停用"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "依赖服务暂时不可用"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
