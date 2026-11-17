package com.githublook.common.web;

import java.util.List;

/** Uniform pagination envelope for list endpoints. */
public record PageResponse<T>(List<T> items, long page, long size, long total, long totalPages) {

    public static <T> PageResponse<T> of(List<T> items, long page, long size, long total) {
        long pages = size <= 0 ? 0 : (long) Math.ceil((double) total / size);
        return new PageResponse<>(items, page, size, total, pages);
    }
}
