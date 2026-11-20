package com.githublook.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.githublook.common.config.AppProperties;
import com.githublook.common.error.AppException;
import com.githublook.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * GitHub REST API 客户端，只封装看板真正会用到的写操作。
 *
 * <p>未配置 token 时 {@link #isConfigured()} 为 false，所有调用都会抛 503，
 * 前端据此把按钮置灰 —— 不存在「点了没反应但提示成功」的情况。
 *
 * <p>鉴权：`Authorization: Bearer <token>`，需要 Actions 读写权限。
 * 注意：单实例直连 api.github.com，未做二级限流；调用量级是个位数/分钟，够用。
 */
@Slf4j
@Service
public class GithubApiClient {

    private final AppProperties props;
    private final RestClient client;
    private final ObjectMapper objectMapper;

    public GithubApiClient(AppProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.getGithub().getConnectTimeout().toMillis());
        factory.setReadTimeout((int) props.getGithub().getReadTimeout().toMillis());
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return props.getGithub().isConfigured();
    }

    public String apiUrl() {
        return props.getGithub().getApiUrl();
    }

    /** 重新运行整个 workflow run（GitHub 会新建一个 run，随 webhook 回流）。 */
    public void rerun(String repoFullName, long runId) {
        post(repoFullName, runId, "rerun", "重新运行");
    }

    /** 取消正在排队/运行中的 run。 */
    public void cancel(String repoFullName, long runId) {
        post(repoFullName, runId, "cancel", "取消运行");
    }

    private void post(String repoFullName, long runId, String action, String label) {
        if (!isConfigured()) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE,
                    "未配置 GITHUB_TOKEN，" + label + "功能未启用");
        }
        String url = "%s/repos/%s/actions/runs/%d/%s".formatted(
                trimTrailingSlash(apiUrl()), repoFullName, runId, action);

        try {
            client.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getGithub().getToken())
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .header(HttpHeaders.USER_AGENT, props.getGithub().getUserAgent())
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .toBodilessEntity();
            log.info("github_api_ok action={} repo={} run={}", action, repoFullName, runId);
        } catch (RestClientResponseException e) {
            String detail = extractMessage(e.getResponseBodyAsString());
            log.warn("github_api_failed action={} repo={} run={} status={} detail={}",
                    action, repoFullName, runId, e.getStatusCode().value(), detail);
            throw translate(e.getStatusCode().value(), label, detail);
        } catch (RuntimeException e) {
            log.error("github_api_unreachable action={} repo={} run={}", action, repoFullName, runId, e);
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE,
                    "无法连接 GitHub API，请检查网络或 app.github.api-url 配置", e);
        }
    }

    private AppException translate(int status, String label, String detail) {
        String suffix = detail == null || detail.isBlank() ? "" : "（GitHub 返回：" + detail + "）";
        return switch (status) {
            case 401 -> new AppException(ErrorCode.UNAUTHORIZED, "GitHub Token 无效或已过期" + suffix);
            case 403 -> new AppException(ErrorCode.FORBIDDEN,
                    "Token 权限不足，" + label + "需要 Actions: write 权限" + suffix);
            case 404 -> new AppException(ErrorCode.NOT_FOUND,
                    "GitHub 上找不到该运行，或 Token 无权访问该仓库" + suffix);
            case 409 -> new AppException(ErrorCode.CONFLICT,
                    "该运行当前状态不允许" + label + suffix);
            case 422 -> new AppException(ErrorCode.BAD_REQUEST, "GitHub 拒绝了该操作" + suffix);
            default -> new AppException(ErrorCode.SERVICE_UNAVAILABLE,
                    "GitHub API 返回 " + status + suffix);
        };
    }

    /** GitHub 错误体形如 {"message": "...", "documentation_url": "..."} */
    private String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode message = node.get("message");
            return message == null ? null : message.asText();
        } catch (Exception e) {
            return body.length() > 200 ? body.substring(0, 200) : body;
        }
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
