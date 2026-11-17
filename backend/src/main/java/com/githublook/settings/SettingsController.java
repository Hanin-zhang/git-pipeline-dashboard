package com.githublook.settings;

import com.githublook.auth.LoginRateLimiter;
import com.githublook.common.config.AppProperties;
import com.githublook.common.error.AppException;
import com.githublook.common.error.ErrorCode;
import com.githublook.common.time.TimeMapper;
import com.githublook.repo.RepoService;
import com.githublook.settings.dto.GateState;
import com.githublook.settings.dto.NotificationDto;
import com.githublook.settings.dto.SettingsDto;
import com.githublook.settings.dto.UnlockRequest;
import com.githublook.settings.dto.UpdateNotificationRequest;
import com.githublook.stream.SseHub;
import com.githublook.webhook.DeliveryAuditService;
import com.githublook.webhook.WebhookConsumer;
import com.githublook.webhook.WebhookQueue;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppProperties props;
    private final RepoService repoService;
    private final NotificationService notificationService;
    private final WebhookQueue queue;
    private final WebhookConsumer consumer;
    private final DeliveryAuditService audit;
    private final SseHub sseHub;
    private final TimeMapper time;
    private final SettingsGateService gate;
    private final LoginRateLimiter rateLimiter;

    /* ---------------- 二次验证门禁 ---------------- */

    /**
     * 解锁入口：验证「设置页访问密码」，成功后种一个短期 Cookie。
     * 该接口本身只需登录即可访问（否则永远解不开），但同样受登录限流保护。
     */
    @PostMapping("/unlock")
    public GateState unlock(@Valid @RequestBody UnlockRequest request,
                            HttpServletRequest httpRequest,
                            HttpServletResponse httpResponse,
                            Authentication authentication) {
        String client = "settings:" + clientKey(httpRequest);
        rateLimiter.check(client);

        if (!gate.verifyPassword(request.password())) {
            rateLimiter.recordFailure(client);
            throw new AppException(ErrorCode.UNAUTHORIZED, "访问密码错误");
        }
        rateLimiter.reset(client);

        String username = authentication == null ? "admin" : authentication.getName();
        String token = gate.issueToken(username);
        ResponseCookie cookie = cookie(token, gate.ttl().getSeconds());
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return new GateState(false, gate.expiresAtOf(token).orElse(gate.expiresAt()),
                (int) gate.ttl().toMinutes());
    }

    @PostMapping("/lock")
    public GateState lock(HttpServletResponse httpResponse) {
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
        return new GateState(true, null, (int) gate.ttl().toMinutes());
    }

    /** 前端进页面先问一次：当前是否已解锁、什么时候过期。 */
    @GetMapping("/state")
    public GateState state(HttpServletRequest httpRequest) {
        String token = resolveCookie(httpRequest, props.getSecurity().getSettingsCookieName());
        boolean valid = gate.isValid(token);
        return new GateState(!valid,
                valid ? gate.expiresAtOf(token).orElse(null) : null,
                (int) gate.ttl().toMinutes());
    }

    /* ---------------- 设置内容（需要已解锁） ---------------- */

    @GetMapping
    public SettingsDto settings() {
        String path = props.getWebhook().getPath();
        String baseUrl = props.getPublicBaseUrl();
        String normalizedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        return new SettingsDto(
                path,
                normalizedBase + path,
                maskSecret(props.getWebhook().getDefaultSecret()),
                repoService.list(),
                notificationService.list(),
                runtime());
    }

    @GetMapping("/runtime")
    public SettingsDto.RuntimeInfo runtimeInfo() {
        return runtime();
    }

    @PutMapping("/notifications/{channel}")
    public NotificationDto updateNotification(@PathVariable String channel,
                                              @Valid @RequestBody UpdateNotificationRequest request) {
        return notificationService.update(channel, request);
    }

    private SettingsDto.RuntimeInfo runtime() {
        Map<String, Long> deliveries = new LinkedHashMap<>();
        deliveries.put("processed", audit.countByStatus("PROCESSED"));
        deliveries.put("queued", audit.countByStatus("QUEUED"));
        deliveries.put("failed", audit.countByStatus("FAILED"));
        deliveries.put("ignored", audit.countByStatus("IGNORED"));

        return new SettingsDto.RuntimeInfo(
                time.now().format(TS),
                time.zone().getId(),
                Math.max(queue.size(), 0),
                props.getQueue().getConsumers(),
                consumer.processedCount(),
                consumer.failedCount(),
                deliveries,
                sseHub.connectionCount());
    }

    private ResponseCookie cookie(String token, long maxAgeSeconds) {
        return ResponseCookie.from(props.getSecurity().getSettingsCookieName(), token)
                .httpOnly(true)
                .secure(props.getSecurity().isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }

    private String resolveCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        return secret.length() <= 6 ? "****" : secret.substring(0, 4) + "****" + secret.substring(secret.length() - 4);
    }
}
