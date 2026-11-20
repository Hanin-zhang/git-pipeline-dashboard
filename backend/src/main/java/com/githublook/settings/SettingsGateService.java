package com.githublook.settings;

import com.githublook.auth.JwtService;
import com.githublook.common.config.AppProperties;
import com.githublook.common.time.TimeMapper;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 「设置与接入」页的二次验证。
 *
 * <p>与登录密码完全独立：登录只代表「你是谁」，解锁才代表「你被允许看 Secret / 改仓库 / 配通知」。
 * 密码来自配置文件 `app.security.settings-password`，最终由环境变量 SETTINGS_ACCESS_PASSWORD 注入。
 *
 * <p>解锁凭证是一个作用域为 settings 的短期 JWT，放在独立的 httpOnly Cookie 里，
 * 到期自动失效，页面会回到输入密码的状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsGateService {

    private static final String SCOPE = "settings";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppProperties props;
    private final JwtService jwtService;
    private final TimeMapper time;

    /**
     * 常量时间比较，避免通过响应耗时逐字节猜密码。
     * 先各自做 SHA-256 再比对，顺带规避长度差异带来的信息泄漏。
     */
    public boolean verifyPassword(String raw) {
        String expected = props.getSecurity().getSettingsPassword();
        if (expected == null || expected.isBlank()) {
            log.error("settings_gate_not_configured");
            return false;
        }
        return MessageDigest.isEqual(sha256(expected), sha256(raw == null ? "" : raw));
    }

    public String issueToken(String username) {
        return jwtService.issueScoped(username, SCOPE, ttl());
    }

    public boolean isValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Optional<Claims> claims = jwtService.parseWithScope(token, SCOPE);
        return claims.isPresent();
    }

    public Duration ttl() {
        return props.getSecurity().getSettingsTokenTtl();
    }

    public String expiresAt() {
        return time.now().plus(ttl()).format(TS);
    }

    /** 从令牌里读真实过期时间，比「现在 + TTL」更准（页面刷新后倒计时不会跳）。 */
    public Optional<String> expiresAtOf(String token) {
        return jwtService.parseWithScope(token, SCOPE)
                .map(claims -> claims.getExpiration().toInstant())
                .map(instant -> java.time.LocalDateTime.ofInstant(instant, time.zone()).format(TS));
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
