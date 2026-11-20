package com.githublook.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.githublook.common.config.AppProperties;
import com.githublook.common.error.AppException;
import com.githublook.common.error.ErrorCode;
import com.githublook.webhook.payload.GithubJob;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用真实 HTTP 桩验证：请求路径/鉴权头是否正确，以及 GitHub 的错误码有没有被翻译成
 * 前端能理解的中文错误 —— 这块最容易出现「点了没反应但提示成功」的假象。
 */
@DisplayName("GithubApiClient · REST 调用与错误翻译")
class GithubApiClientTest {

    private HttpServer server;
    private final AppProperties props = new AppProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<String> secondPageBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicInteger hits = new AtomicInteger();

    private GithubApiClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        props.getGithub().setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        props.getGithub().setToken("ghp_" + "a".repeat(36));
        props.getGithub().setUserAgent("github-look-test");
        client = new GithubApiClient(props, objectMapper);
    }

    private void handle(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        String query = exchange.getRequestURI().getQuery();
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                + (query == null ? "" : "?" + query));
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));

        String bodyText = responseBody.get();
        if (query != null && query.contains("page=2") && secondPageBody.get() != null) {
            bodyText = secondPageBody.get();
        }
        byte[] body = bodyText.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status.get(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("未配置 token：rerun / cancel / listJobs 全部 503，绝不伪造成功")
    void refusesWhenNotConfigured() {
        props.getGithub().setToken("");
        GithubApiClient unconfigured = new GithubApiClient(props, objectMapper);

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.rerun("acme/app", 1L))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        assertThatThrownBy(() -> unconfigured.cancel("acme/app", 1L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("未配置 GITHUB_TOKEN");
        assertThatThrownBy(() -> unconfigured.listJobs("acme/app", 1L))
                .isInstanceOf(AppException.class);
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("rerun 走 POST /repos/{owner}/{repo}/actions/runs/{id}/rerun 并带 Bearer 头")
    void rerunSendsCorrectRequest() {
        client.rerun("acme/app", 42L);

        assertThat(requests).containsExactly("POST /repos/acme/app/actions/runs/42/rerun");
        assertThat(lastAuthorization.get()).isEqualTo("Bearer " + props.getGithub().getToken());
    }

    @Test
    @DisplayName("cancel 走 POST .../cancel")
    void cancelSendsCorrectRequest() {
        client.cancel("acme/app", 42L);

        assertThat(requests).containsExactly("POST /repos/acme/app/actions/runs/42/cancel");
    }

    @Test
    @DisplayName("apiUrl 末尾多余斜杠不会拼出双斜杠")
    void trimsTrailingSlash() {
        props.getGithub().setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "///");
        client = new GithubApiClient(props, objectMapper);

        client.rerun("acme/app", 1L);

        assertThat(requests.get(0)).isEqualTo("POST /repos/acme/app/actions/runs/1/rerun");
    }

    @Test
    @DisplayName("401 → UNAUTHORIZED，并带出 GitHub 的原始 message")
    void translatesUnauthorized() {
        status.set(401);
        responseBody.set("{\"message\":\"Bad credentials\"}");

        assertThatThrownBy(() -> client.rerun("acme/app", 1L))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> client.rerun("acme/app", 1L))
                .hasMessageContaining("Bad credentials");
    }

    @Test
    @DisplayName("403 → FORBIDDEN，并提示需要 Actions: write")
    void translatesForbidden() {
        status.set(403);
        responseBody.set("{\"message\":\"Resource not accessible by integration\"}");

        assertThatThrownBy(() -> client.rerun("acme/app", 1L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("Actions: write")
                .hasMessageContaining("Resource not accessible");
    }

    @Test
    @DisplayName("404 → NOT_FOUND")
    void translatesNotFound() {
        status.set(404);
        responseBody.set("{\"message\":\"Not Found\"}");

        assertThatThrownBy(() -> client.cancel("acme/app", 1L))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("409 → CONFLICT（运行状态不允许该操作）")
    void translatesConflict() {
        status.set(409);

        assertThatThrownBy(() -> client.cancel("acme/app", 1L))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("422 → BAD_REQUEST")
    void translatesUnprocessable() {
        status.set(422);

        assertThatThrownBy(() -> client.rerun("acme/app", 1L))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("5xx → SERVICE_UNAVAILABLE，并回显状态码")
    void translatesServerError() {
        status.set(500);

        assertThatThrownBy(() -> client.rerun("acme/app", 1L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("网络不可达 → SERVICE_UNAVAILABLE，提示检查网络或 api-url")
    void handlesUnreachableHost() {
        props.getGithub().setApiUrl("http://127.0.0.1:1");
        GithubApiClient unreachable = new GithubApiClient(props, objectMapper);

        assertThatThrownBy(() -> unreachable.rerun("acme/app", 1L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("无法连接 GitHub API");
    }

    @Test
    @DisplayName("错误体不是 JSON 时截断原始文本，不把整页 HTML 抛给前端")
    void fallsBackForNonJsonErrorBody() {
        status.set(400);
        responseBody.set("<html>" + "x".repeat(500) + "</html>");

        assertThatThrownBy(() -> client.rerun("acme/app", 1L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("GitHub API 返回 400");
    }

    @Test
    @DisplayName("listJobs 分页拉取，最多 5 页，凑满 total_count 即停")
    void paginatesListJobs() {
        status.set(200);
        responseBody.set(jobsJson(100, 150));
        secondPageBody.set(jobsJson(50, 150));

        List<GithubJob> jobs = client.listJobs("acme/app", 7L);

        assertThat(jobs).hasSize(150);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0)).contains("/repos/acme/app/actions/runs/7/jobs?per_page=100&page=1");
        assertThat(requests.get(1)).contains("page=2");
    }

    @Test
    @DisplayName("listJobs 单页不足 100 条时不发第二次请求")
    void stopsWhenPageIsShort() {
        responseBody.set(jobsJson(3, 3));

        List<GithubJob> jobs = client.listJobs("acme/app", 7L);

        assertThat(jobs).hasSize(3);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("listJobs 返回空列表时正常结束（新仓库首次运行可能没有 job）")
    void handlesEmptyJobs() {
        responseBody.set("{\"total_count\":0,\"jobs\":[]}");

        assertThat(client.listJobs("acme/app", 7L)).isEmpty();
    }

    @Test
    @DisplayName("listJobs 遇到 404 也走统一错误翻译")
    void listJobsTranslatesErrors() {
        status.set(404);
        responseBody.set("{\"message\":\"Not Found\"}");

        assertThatThrownBy(() -> client.listJobs("acme/app", 7L))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("isConfigured 只取决于 token 是否为空")
    void reportsConfigurationState() {
        assertThat(client.isConfigured()).isTrue();
        props.getGithub().setToken("   ");
        assertThat(new GithubApiClient(props, objectMapper).isConfigured()).isFalse();
    }

    @Test
    @DisplayName("apiUrl 原样暴露给能力接口，前端用来展示")
    void exposesApiUrl() {
        assertThat(client.apiUrl()).startsWith("http://127.0.0.1:");
    }

    private String jobsJson(int count, int total) {
        StringBuilder sb = new StringBuilder("{\"total_count\":").append(total).append(",\"jobs\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(i + 1)
                    .append(",\"name\":\"job-").append(i + 1).append("\",\"status\":\"completed\",")
                    .append("\"conclusion\":\"success\",\"runner_name\":\"ubuntu-latest\"}");
        }
        return sb.append("]}").toString();
    }
}
