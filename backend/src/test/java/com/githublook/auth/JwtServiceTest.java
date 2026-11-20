package com.githublook.auth;

import com.githublook.common.config.AppProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtService · 令牌签发与校验")
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-at-least-32-bytes-long!!";

    private final AppProperties props = new AppProperties();
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        props.getSecurity().setJwtSecret(SECRET);
        props.getSecurity().setAccessTokenTtl(Duration.ofHours(8));
        jwtService = new JwtService(props);
    }

    @Test
    @DisplayName("签发的登录令牌可以解析出 subject 与 role")
    void issuesAndParsesLoginToken() {
        String token = jwtService.issue("admin", "ADMIN");

        Optional<Claims> claims = jwtService.parse(token);

        assertThat(claims).isPresent();
        assertThat(claims.get().getSubject()).isEqualTo("admin");
        assertThat(claims.get().get("role", String.class)).isEqualTo("ADMIN");
        assertThat(claims.get().getExpiration()).isAfter(new java.util.Date());
    }

    @Test
    @DisplayName("令牌有效期跟随 access-token-ttl 配置")
    void respectsConfiguredTtl() {
        props.getSecurity().setAccessTokenTtl(Duration.ofMinutes(5));

        Claims claims = jwtService.parse(jwtService.issue("admin", "ADMIN")).orElseThrow();
        long seconds = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;

        assertThat(seconds).isEqualTo(300);
        assertThat(jwtService.ttl()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("被改动的令牌一律拒绝（签名校验失败）")
    void rejectsTamperedToken() {
        String token = jwtService.issue("admin", "ADMIN");
        String tampered = token.substring(0, token.length() - 2) + "xy";

        assertThat(jwtService.parse(tampered)).isEmpty();
    }

    @Test
    @DisplayName("换一把密钥签发的令牌拒绝（防止密钥轮换后旧令牌继续可用）")
    void rejectsTokenSignedWithAnotherKey() {
        String foreign = jwtService.issue("admin", "ADMIN");

        AppProperties other = new AppProperties();
        other.getSecurity().setJwtSecret("another-secret-also-32-bytes-long-xxxx");
        JwtService otherService = new JwtService(other);

        assertThat(otherService.parse(foreign)).isEmpty();
    }

    @Test
    @DisplayName("垃圾字符串不会抛异常，只会被当作未登录")
    void rejectsGarbage() {
        assertThat(jwtService.parse("not-a-jwt")).isEmpty();
        assertThat(jwtService.parse("")).isEmpty();
        assertThat(jwtService.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("已过期的令牌拒绝")
    void rejectsExpiredToken() {
        String expired = jwtService.issueScoped("admin", "settings", Duration.ofSeconds(-1));

        assertThat(jwtService.parse(expired)).isEmpty();
    }

    @Test
    @DisplayName("作用域令牌：scope 匹配才放行")
    void parsesScopedTokenOnlyForMatchingScope() {
        String token = jwtService.issueScoped("admin", "settings", Duration.ofMinutes(30));

        assertThat(jwtService.parseWithScope(token, "settings")).isPresent();
        assertThat(jwtService.parseWithScope(token, "other")).isEmpty();
    }

    @Test
    @DisplayName("登录令牌不带 scope，不能当设置页解锁凭证用")
    void loginTokenIsNotASettingsToken() {
        String login = jwtService.issue("admin", "ADMIN");

        assertThat(jwtService.parseWithScope(login, "settings")).isEmpty();
    }

    @Test
    @DisplayName("设置页令牌也不能当登录令牌用（role 缺失）")
    void scopedTokenHasNoRoleClaim() {
        String scoped = jwtService.issueScoped("admin", "settings", Duration.ofMinutes(30));

        Claims claims = jwtService.parse(scoped).orElseThrow();

        assertThat(claims.get("role", String.class)).isNull();
    }
}
