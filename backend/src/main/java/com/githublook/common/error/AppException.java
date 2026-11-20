package com.githublook.common.error;

import lombok.Getter;

/** Business failure carrying a stable client-facing code. */
@Getter
public class AppException extends RuntimeException {

    private final ErrorCode code;

    public AppException(ErrorCode code) {
        super(code.defaultMessage());
        this.code = code;
    }

    public AppException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public AppException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public static AppException notFound(String what) {
        return new AppException(ErrorCode.NOT_FOUND, what + "不存在");
    }

    public static AppException badRequest(String message) {
        return new AppException(ErrorCode.BAD_REQUEST, message);
    }

    public static AppException conflict(String message) {
        return new AppException(ErrorCode.CONFLICT, message);
    }
}
