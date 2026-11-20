package com.githublook.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.githublook.common.config.AppProperties;
import com.githublook.common.error.AppException;
import com.githublook.common.error.ErrorCode;
import com.githublook.webhook.payload.GithubJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

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
    /** 拉日志专用：需要手动处理 302，且跳转后不能带 token */
    private final java.net.http.HttpClient http;

    /** 日志下载单独的超时：全局 read-timeout 只有 10 秒，几 MB 的日志拉不完 */
    private static final java.time.Duration LOG_TIMEOUT = java.time.Duration.ofSeconds(60);
    private static final int LOG_ATTEMPTS = 3;
    private final ObjectMapper objectMapper;

    public GithubApiClient(AppProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;

        // 用 JDK HttpClient 而不是 HttpURLConnection（SimpleClientHttpRequestFactory）：
        // 后者在 4xx/5xx 时会丢掉响应体，导致下面 translate() 里
        // 「（GitHub 返回：...）」永远是空的，403 权限问题几乎无法自查。
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(props.getGithub().getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(props.getGithub().getReadTimeout());
        this.client = RestClient.builder().requestFactory(factory).build();
        this.http = httpClient;
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

    /**
     * 拉取某个 run 的 job + step 明细。
     *
     * <p>这是补充 DAG 的唯一可靠来源：`workflow_run` 事件本身不携带 jobs，
     * 只有 Actions API 有。注意该接口**不返回 needs**，所以依赖关系仍然是未知的，
     * 前端会据此标注「按执行顺序展示」而不是假装有依赖。
     */
    public List<GithubJob> listJobs(String repoFullName, long runId) {
        if (!isConfigured()) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE,
                    "未配置 GITHUB_TOKEN，无法从 GitHub 同步 Job 明细");
        }

        List<GithubJob> all = new java.util.ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            String url = "%s/repos/%s/actions/runs/%d/jobs?per_page=100&page=%d".formatted(
                    trimTrailingSlash(apiUrl()), repoFullName, runId, page);
            JobsResponse response;
            try {
                response = client.get()
                        .uri(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getGithub().getToken())
                        .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                        .header(HttpHeaders.USER_AGENT, props.getGithub().getUserAgent())
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .retrieve()
                        .body(JobsResponse.class);
            } catch (RestClientResponseException e) {
                String detail = extractMessage(e.getResponseBodyAsString());
                log.warn("github_jobs_failed repo={} run={} status={} detail={}",
                        repoFullName, runId, e.getStatusCode().value(), detail);
                throw translate(e.getStatusCode().value(), "同步 Job 明细", detail);
            } catch (RuntimeException e) {
                log.error("github_jobs_unreachable repo={} run={}", repoFullName, runId, e);
                throw new AppException(ErrorCode.SERVICE_UNAVAILABLE,
                        "无法连接 GitHub API，请检查网络或 app.github.api-url 配置", e);
            }

            if (response == null || response.jobs() == null || response.jobs().isEmpty()) {
                break;
            }
            all.addAll(response.jobs());
            if (response.jobs().size() < 100 || all.size() >= response.totalCount()) {
                break;
            }
        }
        log.info("github_jobs_fetched repo={} run={} jobs={}", repoFullName, runId, all.size());
        return all;
    }

    /** /actions/runs/{id}/jobs 的响应外壳 */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record JobsResponse(int totalCount, List<GithubJob> jobs) {
        @com.fasterxml.jackson.annotation.JsonCreator
        public JobsResponse(@com.fasterxml.jackson.annotation.JsonProperty("total_count") int totalCount,
                            @com.fasterxml.jackson.annotation.JsonProperty("jobs") List<GithubJob> jobs) {
            this.totalCount = totalCount;
            this.jobs = jobs;
        }
    }

    /**
     * 拉取单个 job 的标准输出日志。
     *
     * <p>GitHub 这个接口返回 302 跳转到签名过的 blob 地址，正文是纯文本。
     * 底层 HttpURLConnection 会自动跟随 GET 的跳转，所以这里直接取字符串。
     * 日志体积不可控（几十 KB 到十几 MB），这里按 2MB 截断，避免把内存和响应拖垮。
     */
    /**
     * 拉取单个 job 的标准输出日志。
     *
     * <p>GitHub 这个接口返回 302 跳转到签名过的 blob 地址，正文是纯文本。
     * 必须手动跟随：JDK HttpClient 默认不跟随跳转，而且自动跟随会把
     * Authorization 头一起带到 blob 存储上（凭证外泄）。
     *
     * <p>两个稳定性措施：
     * <ul>
     *   <li>日志下载单独用 60 秒超时 —— 全局 read-timeout 只有 10 秒，几 MB 的日志拉不完</li>
     *   <li>网络类 IOException 自动重试 3 次 —— blob 存储偶发掐连接很常见</li>
     * </ul>
     */
    public String fetchJobLogs(String repoFullName, long externalJobId) {
        if (!isConfigured()) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE,
                    "未配置 GITHUB_TOKEN，无法拉取执行日志");
        }

        java.io.IOException last = null;
        for (int attempt = 1; attempt <= LOG_ATTEMPTS; attempt++) {
            try {
                return fetchJobLogsOnce(repoFullName, externalJobId);
            } catch (java.io.IOException e) {
                last = e;
                log.warn("github_job_logs_io_retry repo={} job={} attempt={}/{} type={} msg={}",
                        repoFullName, externalJobId, attempt, LOG_ATTEMPTS,
                        e.getClass().getSimpleName(), e.getMessage());
                if (attempt < LOG_ATTEMPTS) {
                    try {
                        Thread.sleep(250L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AppException(ErrorCode.SERVICE_UNAVAILABLE, "拉取日志被中断", e);
            }
        }
        throw new AppException(ErrorCode.SERVICE_UNAVAILABLE,
                "无法连接 GitHub 拉取日志（已重试 " + LOG_ATTEMPTS + " 次）："
                        + (last == null ? "未知原因" : last.getClass().getSimpleName() + " " + last.getMessage()),
                last);
    }

    private String fetchJobLogsOnce(String repoFullName, long externalJobId)
            throws java.io.IOException, InterruptedException {
        String url = "%s/repos/%s/actions/jobs/%d/logs".formatted(
                trimTrailingSlash(apiUrl()), repoFullName, externalJobId);

        // 第一次：只看 302 和 Location
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(LOG_TIMEOUT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getGithub().getToken())
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .header(HttpHeaders.USER_AGENT, props.getGithub().getUserAgent())
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();

        java.net.http.HttpResponse<byte[]> first =
                http.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        int status = first.statusCode();

        byte[] body;
        if (status >= 300 && status < 400) {
            String location = first.headers().firstValue("location").orElse(null);
            if (location == null || location.isBlank()) {
                log.warn("github_job_logs_no_location repo={} job={} status={}",
                        repoFullName, externalJobId, status);
                return "";
            }
            // 第二次：签名地址，绝不能带 Authorization
            java.net.http.HttpRequest download = java.net.http.HttpRequest.newBuilder(java.net.URI.create(location))
                    .timeout(LOG_TIMEOUT)
                    .GET()
                    .build();
            java.net.http.HttpResponse<byte[]> second =
                    http.send(download, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (second.statusCode() >= 500) {
                // 5xx 视为可重试，抛 IO 让外层再试一次
                throw new java.io.IOException("日志存储返回 " + second.statusCode());
            }
            if (second.statusCode() >= 400) {
                log.warn("github_job_logs_download_failed repo={} job={} status={}",
                        repoFullName, externalJobId, second.statusCode());
                throw new AppException(ErrorCode.SERVICE_UNAVAILABLE,
                        "日志下载失败（HTTP " + second.statusCode() + "），可能已过期");
            }
            body = second.body();
        } else if (status == 200) {
            body = first.body();
        } else {
            String detail = new String(first.body() == null ? new byte[0] : first.body(),
                    java.nio.charset.StandardCharsets.UTF_8);
            log.warn("github_job_logs_failed repo={} job={} status={} detail={}",
                    repoFullName, externalJobId, status, detail);
            if (status == 404) {
                throw new AppException(ErrorCode.NOT_FOUND, "日志不存在或已过期（GitHub 只保留 90 天）");
            }
            throw translate(status, "拉取执行日志", extractMessage(detail));
        }

        if (body == null || body.length == 0) {
            log.warn("github_job_logs_empty repo={} job={} status={}", repoFullName, externalJobId, status);
            return "";
        }
        int limit = 2 * 1024 * 1024;
        String text = new String(body, 0, Math.min(body.length, limit), java.nio.charset.StandardCharsets.UTF_8);
        if (body.length > limit) {
            text += "\n\n… 日志超过 2MB，已截断，完整内容请到 GitHub 查看";
        }
        log.info("github_job_logs_ok repo={} job={} bytes={}", repoFullName, externalJobId, body.length);
        return text;
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
