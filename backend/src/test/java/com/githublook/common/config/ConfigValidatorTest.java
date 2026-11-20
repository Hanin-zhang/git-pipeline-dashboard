package com.githublook.common.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConfigValidator · 启动期配置校验")
class ConfigValidatorTest {

    /** 与 application.yml 中 admin-password 的默认值保持一致，用于验证 prod 拒绝逻辑。 */
    private static final String DEFAULT_ADMIN_PASSWORD = "admin123";
    /** 与 application.yml 中 settings-password 的默认值保持一致。 */
    private static final String DEFAULT_SETTINGS_PASSWORD = "settings123";

    @Mock
    private Environment env;

    private final AppProperties props = new AppProperties();
    private ConfigValidator validator;

    @BeforeEach
    void setUp() {
        when(env.getActiveProfiles()).thenReturn(new String[0]);
        props.getSecurity().setJwtSecret("a-very-long-test-secret-with-32-chars!!");
        props.getSecurity().setAllowedOrigins(List.of("http://localhost:5173"));
        props.getSecurity().setAdminPassword("admin-strong-pw");
        props.getSecurity().setSettingsPassword("custom-settings-pw");
        props.getSecurity().setCookieSecure(true);
        props.getWebhook().setDefaultSecret("global-secret");
        validator = new ConfigValidator(props, env);
    }

    private void assertFails(String expectedFragment) {
        assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid configuration")
                .hasMessageContaining(expectedFragment);
    }

    @Test
    @DisplayName("合法配置静默通过")
    void acceptsValidConfiguration() {
        assertThatCode(() -> validator.validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("JWT 密钥短于 32 字符直接拒绝启动（HS256 安全强度要求）")
    void rejectsShortJwtSecret() {
        props.getSecurity().setJwtSecret("too-short");

        assertFails("jwt-secret");
    }

    @Test
    @DisplayName("JWT 密钥为空同样拒绝")
    void rejectsMissingJwtSecret() {
        props.getSecurity().setJwtSecret(null);

        assertFails("jwt-secret");
    }

    @Test
    @DisplayName("CORS 白名单为空会拒绝（凭据请求不能用通配符）")
    void rejectsEmptyAllowedOrigins() {
        props.getSecurity().setAllowedOrigins(List.of());

        assertFails("allowed-origins");
    }

    @Test
    @DisplayName("管理员密码短于 6 位拒绝")
    void rejectsWeakAdminPassword() {
        props.getSecurity().setAdminPassword("12345");

        assertFails("admin-password");
    }

    @Test
    @DisplayName("设置页密码未配置或过短拒绝")
    void rejectsMissingSettingsPassword() {
        props.getSecurity().setSettingsPassword("");

        assertFails("settings-password");
    }

    @Test
    @DisplayName("消费者数量必须 >= 1，否则队列永远不被消费")
    void rejectsZeroConsumers() {
        props.getQueue().setConsumers(0);

        assertFails("consumers");
    }

    @Test
    @DisplayName("轮询间隔必须为正，否则空转会打满 CPU")
    void rejectsNonPositivePollInterval() {
        props.getQueue().setPollInterval(Duration.ZERO);

        assertFails("poll-interval");
    }

    @Test
    @DisplayName("payload 上限不得低于 1KB（GitHub 事件都大于这个量级）")
    void rejectsTinyPayloadLimit() {
        props.getWebhook().setMaxPayloadBytes(100);

        assertFails("max-payload-bytes");
    }

    @Test
    @DisplayName("自动注册开启但没有全局密钥 → 拒绝（否则任意仓库都能注册进来）")
    void rejectsAutoRegisterWithoutDefaultSecret() {
        props.getWebhook().setAutoRegister(true);
        props.getWebhook().setDefaultSecret("");

        assertFails("auto-register");
    }

    @Test
    @DisplayName("自动注册关闭时不要求全局密钥")
    void allowsAutoRegisterOffWithoutSecret() {
        props.getWebhook().setAutoRegister(false);
        props.getWebhook().setDefaultSecret("");

        assertThatCode(() -> validator.validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("配置了 GitHub Token 但长度明显不对 → 拒绝（防止把占位符当 token）")
    void rejectsSuspiciousGithubToken() {
        props.getGithub().setToken("short");

        assertFails("github.token");
    }

    @Test
    @DisplayName("配置了合法长度的 Token 则允许启动")
    void acceptsGithubToken() {
        props.getGithub().setToken("ghp_" + "x".repeat(36));

        assertThatCode(() -> validator.validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("一次报出所有问题，而不是改一个报一个")
    void collectsAllProblems() {
        props.getSecurity().setJwtSecret("short");
        props.getSecurity().setAdminPassword("1");
        props.getQueue().setConsumers(0);

        assertThatThrownBy(() -> validator.validate())
                .hasMessageContaining("jwt-secret")
                .hasMessageContaining("admin-password")
                .hasMessageContaining("consumers");
    }

    @Test
    @DisplayName("prod profile 下拒绝开发用 JWT 密钥")
    void refusesDevSecretInProd() {
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
        props.getSecurity().setJwtSecret("dev-only-secret-please-override-with-32bytes-minimum");

        assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("development JWT secret");
    }

    @Test
    @DisplayName("prod profile 下拒绝默认管理员密码")
    void refusesDefaultAdminPasswordInProd() {
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
        props.getSecurity().setAdminPassword(DEFAULT_ADMIN_PASSWORD);

        assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default admin password");
    }

    @Test
    @DisplayName("prod profile 下拒绝默认设置页密码")
    void refusesDefaultSettingsPasswordInProd() {
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
        props.getSecurity().setSettingsPassword(DEFAULT_SETTINGS_PASSWORD);

        assertThatThrownBy(() -> validator.validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default settings password");
    }

    @Test
    @DisplayName("prod profile 下 cookie-secure=false 只告警不阻断（内网 HTTP 部署仍需可用）")
    void warnsButAllowsInsecureCookieInProd() {
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
        props.getSecurity().setCookieSecure(false);

        assertThatCode(() -> validator.validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("非 prod profile 允许开发默认值，方便本地起服务")
    void allowsDefaultsOutsideProd() {
        when(env.getActiveProfiles()).thenReturn(new String[]{"dev"});
        props.getSecurity().setAdminPassword(DEFAULT_ADMIN_PASSWORD);
        props.getSecurity().setSettingsPassword(DEFAULT_SETTINGS_PASSWORD);

        assertThatCode(() -> validator.validate()).doesNotThrowAnyException();
    }
}
