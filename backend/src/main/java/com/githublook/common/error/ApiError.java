package com.githublook.common.error;

import java.time.Instant;
import java.util.Map;

/**
 * Canonical error body. Field name {@code error} keeps it readable in curl output
 * while {@code code} stays machine friendly.
 */
public record ApiError(
        String error,
        String message,
        String requestId,
        Instant timestamp,
        Map<String, String> details) {

    public static ApiError of(ErrorCode code, String message, String requestId) {
        return new ApiError(code.name(), message, requestId, Instant.now(), null);
    }
}
