package com.githublook.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for every tunable knob. All values come from
 * application.yml and can be overridden by environment variables.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Webhook webhook = new Webhook();
    private Idempotency idempotency = new Idempotency();
    private Queue queue = new Queue();
    private Security security = new Security();
    private Stats stats = new Stats();
    private Demo demo = new Demo();
    private Github github = new Github();

    /** Base URL shown to users when they copy the webhook endpoint (tunnel/domain in production). */
    private String publicBaseUrl = "http://localhost:8080";

    @Getter
    @Setter
    public static class Webhook {
        private String path = "/api/webhooks/github";
        private String signatureHeader = "X-Hub-Signature-256";
        private String deliveryHeader = "X-GitHub-Delivery";
        private String eventHeader = "X-GitHub-Event";
        private int maxPayloadBytes = 1_048_576;
        private String defaultSecret = "";
        /** When true, an unknown repo is registered on first valid delivery (requires default-secret). */
        private boolean autoRegister = true;
    }

    @Getter
    @Setter
    public static class Idempotency {
        private String keyPrefix = "githublook:delivery:";
        private Duration ttl = Duration.ofHours(24);
    }

    @Getter
    @Setter
    public static class Queue {
        private String key = "githublook:webhook:queue";
        private String processingKey = "githublook:webhook:queue:processing";
        private String deadLetterKey = "githublook:webhook:queue:dead";
        private int consumers = 8;
        private Duration pollInterval = Duration.ofMillis(50);
        private int maxAttempts = 3;
    }

    @Getter
    @Setter
    public static class Security {
        private String jwtSecret = "";
        private Duration accessTokenTtl = Duration.ofHours(8);
        private String cookieName = "gl_session";
        private boolean cookieSecure = false;
        private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:5173"));
        private String adminUsername = "admin";
        private String adminPassword = "admin123";

        /**
         * 「设置与接入」页的二次验证密码，与登录密码相互独立。
         * 通过环境变量 SETTINGS_ACCESS_PASSWORD 注入，改密码不需要改代码。
         */
        private String settingsPassword = "";
        /** 解锁有效期：到期后需要重新输入密码 */
        private Duration settingsTokenTtl = Duration.ofMinutes(30);
        private String settingsCookieName = "gl_settings";
    }

    @Getter
    @Setter
    public static class Stats {
        private Duration cacheTtl = Duration.ofSeconds(20);
    }

    @Getter
    @Setter
    public static class Demo {
        private boolean enabled = true;
        private int days = 7;
        private boolean reset = false;
    }

    /**
     * GitHub REST API 客户端配置。token 为空时所有需要调用 GitHub 的操作
     * （重跑 / 取消 / 日志 / Artifact）都会被明确禁用，而不是伪造成功。
     */
    @Getter
    @Setter
    public static class Github {
        /** Personal Access Token 或 GitHub App token；支持 ${GITHUB_TOKEN} 注入。 */
        private String token = "";
        private String apiUrl = "https://api.github.com";
        private String userAgent = "github-look";
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(10);

        public boolean isConfigured() {
            return token != null && !token.isBlank();
        }
    }
}
