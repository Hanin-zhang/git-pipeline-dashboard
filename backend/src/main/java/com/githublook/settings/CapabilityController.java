package com.githublook.settings;

import com.githublook.common.config.AppProperties;
import com.githublook.github.GithubApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 前端启动时读一次，用来决定哪些操作按钮可以点亮。
 * 能力开关全部来自服务端真实配置，前端不猜测也不兜底伪造。
 */
@RestController
@RequestMapping("/api/capabilities")
@RequiredArgsConstructor
public class CapabilityController {

    private final AppProperties props;
    private final GithubApiClient github;

    public record CapabilitiesDto(
            boolean githubActions,
            String githubApiUrl,
            List<String> enabledActions,
            List<String> disabledReasons) {
    }

    @GetMapping
    public CapabilitiesDto capabilities() {
        boolean configured = github.isConfigured();
        List<String> enabled = configured
                ? List.of("rerun", "cancel")
                : List.of();
        List<String> reasons = configured
                ? List.of()
                : List.of(
                        "rerun: 未配置 GITHUB_TOKEN",
                        "cancel: 未配置 GITHUB_TOKEN",
                        "logs: 需要接 GitHub Actions Logs API",
                        "artifacts: 需要接 GitHub Actions Artifacts API");

        return new CapabilitiesDto(
                configured,
                props.getGithub().getApiUrl(),
                enabled,
                reasons);
    }
}
