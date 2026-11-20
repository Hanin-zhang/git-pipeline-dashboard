package com.githublook.settings;

import com.githublook.auth.JwtService;
import com.githublook.common.config.AppProperties;
import com.githublook.common.time.TimeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SettingsGateService · 设置页二次验证")
class SettingsGateServiceTest {

    private final AppProperties props = new AppProperties();
    private final TimeMapper time = new TimeMapper("Asia/Shanghai");
    private JwtService jwtService;
    private SettingsGateService gate;

    @BeforeEach
    void setUp() {
        props.getSecurity().setJwtSecret("settings-gate-test-secret-32-bytes-min!!");
        props.getSecurity().setSettingsPassword("settings123");
        props.getSecurity().setSettingsTokenTtl(Duration.ofMinutes(30));
        jwtService = new JwtService(props);
        gate = new SettingsGateService(props, jwtService, time);
    }

    @Test
    @DisplayName("密码正确才通过")
    void verifiesPassword() {
        assertThat(gate.verifyPassword("settings123")).isTrue();
        assertThat(gate.verifyPassword("settings124")).isFalse();
        assertThat(gate.verifyPassword("")).isFalse();
        assertThat(gate.verifyPassword(null)).isFalse();
    }

    @Test
    @DisplayName("配置为空时永远拒绝（避免空密码被当成万能钥匙）")
    void rejectsWhenPasswordNotConfigured() {
        props.getSecurity().setSettingsPassword("");
        assertThat(gate.verifyPassword("")).isFalse();
        assertThat(gate.verifyPassword("anything")).isFalse();

        props.getSecurity().setSettingsPassword(null);
        assertThat(gate.verifyPassword("anything")).isFalse();
    }

    @Test
    @DisplayName("密码区分大小写与前缀")
    void passwordComparisonIsExact() {
        assertThat(gate.verifyPassword("Settings123")).isFalse();
        assertThat(gate.verifyPassword("settings1234")).isFalse();
        assertThat(gate.verifyPassword(" settings123")).isFalse();
    }

    @Test
    @DisplayName("解锁令牌可以校验通过")
    void issuedTokenIsValid() {
        String token = gate.issueToken("admin");

        assertThat(gate.isValid(token)).isTrue();
    }

    @Test
    @DisplayName("空令牌 / 登录令牌 / 别人的令牌都不算解锁")
    void rejectsForeignOrMissingTokens() {
        String loginToken = jwtService.issue("admin", "ADMIN");

        assertThat(gate.isValid(null)).isFalse();
        assertThat(gate.isValid("")).isFalse();
        assertThat(gate.isValid("  ")).isFalse();
        assertThat(gate.isValid(loginToken)).isFalse();
        assertThat(gate.isValid("garbage")).isFalse();
    }

    @Test
    @DisplayName("解锁有效期默认 30 分钟，过期后 isValid 变 false")
    void tokenExpiresAccordingToTtl() {
        props.getSecurity().setSettingsTokenTtl(Duration.ofSeconds(-1));

        assertThat(gate.isValid(gate.issueToken("admin"))).isFalse();
        assertThat(gate.ttl()).isEqualTo(Duration.ofSeconds(-1));
    }

    @Test
    @DisplayName("expiresAt 输出 UTC+8 的可读时间")
    void formatsExpiry() {
        assertThat(gate.expiresAt()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("expiresAtOf 从令牌里读真实过期时间；非解锁令牌返回空")
    void readsExpiryFromToken() {
        String token = gate.issueToken("admin");

        assertThat(gate.expiresAtOf(token)).isPresent();
        assertThat(gate.expiresAtOf(token).orElseThrow()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        assertThat(gate.expiresAtOf(jwtService.issue("admin", "ADMIN"))).isEmpty();
        assertThat(gate.expiresAtOf(null)).isEmpty();
    }
}
