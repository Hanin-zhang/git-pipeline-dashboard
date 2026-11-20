package com.githublook.common.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fails fast at startup when the configuration is unusable, instead of
 * blowing up on the first request in production.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigValidator {

    private final AppProperties props;
    private final Environment env;

    @PostConstruct
    void validate() {
        List<String> problems = new java.util.ArrayList<>();

        AppProperties.Security sec = props.getSecurity();
        if (sec.getJwtSecret() == null || sec.getJwtSecret().length() < 32) {
            problems.add("app.security.jwt-secret must be at least 32 characters (set APP_JWT_SECRET)");
        }
        if (sec.getAllowedOrigins() == null || sec.getAllowedOrigins().isEmpty()) {
            problems.add("app.security.allowed-origins must not be empty");
        }
        if (sec.getAdminPassword() == null || sec.getAdminPassword().length() < 6) {
            problems.add("app.security.admin-password must be at least 6 characters");
        }
        if (sec.getSettingsPassword() == null || sec.getSettingsPassword().length() < 6) {
            problems.add("app.security.settings-password must be at least 6 characters (set SETTINGS_ACCESS_PASSWORD)");
        }
        if (props.getQueue().getConsumers() < 1) {
            problems.add("app.queue.consumers must be >= 1");
        }
        if (props.getQueue().getPollInterval().isNegative() || props.getQueue().getPollInterval().isZero()) {
            problems.add("app.queue.poll-interval must be positive");
        }
        if (props.getWebhook().getMaxPayloadBytes() < 1024) {
            problems.add("app.webhook.max-payload-bytes must be >= 1024");
        }
        if (props.getWebhook().isAutoRegister()
                && (props.getWebhook().getDefaultSecret() == null || props.getWebhook().getDefaultSecret().isBlank())) {
            problems.add("app.webhook.auto-register requires app.webhook.default-secret, otherwise unsigned repos could register");
        }

        AppProperties.Github github = props.getGithub();
        if (github.isConfigured()) {
            if (github.getToken().length() < 20) {
                problems.add("app.github.token looks too short to be a valid GitHub token");
            }
            // 只打印是否启用，绝不打印 token 本身
            log.info("GitHub API enabled: rerun / cancel / logs / artifacts available (api={})", github.getApiUrl());
        } else {
            log.info("GitHub API disabled (no GITHUB_TOKEN): rerun / cancel / logs / artifacts are turned off in the UI");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid configuration:\n - " + String.join("\n - ", problems));
        }

        boolean prod = List.of(env.getActiveProfiles()).contains("prod");
        if (prod) {
            if (sec.getJwtSecret().startsWith("dev-only")) {
                throw new IllegalStateException("Refusing to start with the development JWT secret in the prod profile");
            }
            if ("admin123".equals(sec.getAdminPassword())) {
                throw new IllegalStateException("Refusing to start with the default admin password in the prod profile");
            }
            if ("settings123".equals(sec.getSettingsPassword())) {
                throw new IllegalStateException("Refusing to start with the default settings password in the prod profile");
            }
            if (!sec.isCookieSecure()) {
                log.warn("app.security.cookie-secure is false while running in prod: session cookies will be sent over plain HTTP");
            }
        }

        log.info("Configuration validated: db={}, redis={}:{}, queue={}, consumers={}",
                env.getProperty("spring.datasource.url"),
                env.getProperty("spring.data.redis.host"),
                env.getProperty("spring.data.redis.port"),
                props.getQueue().getKey(),
                props.getQueue().getConsumers());
    }
}
