package com.githublook.common.time;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * All timestamps are stored and displayed in one configured zone, so a build that
 * started at 00:10 local time never lands in "yesterday" because of a UTC offset.
 */
@Component
public class TimeMapper {

    private final ZoneId zoneId;

    public TimeMapper(@Value("${app.zone-id:Asia/Shanghai}") String zoneId) {
        this.zoneId = ZoneId.of(zoneId);
    }

    public ZoneId zone() {
        return zoneId;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(zoneId);
    }

    public LocalDate today() {
        return LocalDate.now(zoneId);
    }

    public LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, zoneId);
    }
}
