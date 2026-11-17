package com.forecast.task.client;

import com.forecast.api.client.ForecastClientException;
import com.forecast.api.client.ForecastTaskClient;
import com.forecast.api.client.ForecastTaskClients;
import com.forecast.api.dto.ForecastTaskRequest;
import com.forecast.api.dto.TaskSubmitResult;
import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import com.forecast.common.model.R;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * forecast-api 声明式客户端（HTTP Interface）的请求构造与错误处理。
 */
class ForecastTaskClientsTest {

    private HttpServer server;
    private final Map<String, String> captured = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/forecast/LOAD/full", ex -> {
            captured.put("method", ex.getRequestMethod());
            captured.put("query", String.valueOf(ex.getRequestURI().getQuery()));
            captured.put("apiKey", String.valueOf(ex.getRequestHeaders().getFirst("X-API-Key")));
            captured.put("body", new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"code\":0,\"message\":\"success\",\"data\":{\"taskId\":\"FT1\",\"status\":\"PENDING\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/api/v1/tasks/LIMITED", ex -> {
            byte[] body = "{\"code\":10429,\"message\":\"系统繁忙\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            ex.sendResponseHeaders(429, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        json(500, "/api/v1/tasks/BOOT-ERROR", "application/json",
                "{\"timestamp\":\"2026-10-09T15:01:51.437+00:00\",\"path\":\"/api/v1/tasks/FT1\",\"status\":500,"
                        + "\"error\":\"Internal Server Error\",\"requestId\":\"d790e856-2\"}");
        json(503, "/api/v1/tasks/NO-CODE", "application/json", "{\"message\":\"no healthy upstream\"}");
        json(502, "/api/v1/tasks/EMPTY", "application/json", "");
        json(500, "/api/v1/tasks/ZERO-CODE", "application/json", "{\"code\":0,\"message\":\"success\"}");
        json(400, "/api/v1/tasks/STRING-CODE", "application/json", "{\"code\":\"10400\",\"message\":\"x\"}");
        // 内容像 R 但不是 JSON 响应（代理返回的纯文本、缺失 Content-Type）：不能按 R 解码
        json(503, "/api/v1/tasks/TEXT-R", "text/plain", "{\"code\":10500,\"message\":\"x\"}");
        json(500, "/api/v1/tasks/NO-TYPE-R", null, "{\"code\":10500,\"message\":\"x\"}");
        json(503, "/api/v1/tasks/PROBLEM-R", "application/problem+json", "{\"code\":40503,\"message\":\"引擎不可用\"}");
        server.createContext("/api/v1/tasks/TRUNCATED", ex -> {
            // 声明 100 字节却只写 10 字节后断开：模拟读取错误响应体时连接中断
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(500, 100);
            ex.getResponseBody().write("{\"code\":10".getBytes(StandardCharsets.UTF_8));
            ex.getResponseBody().flush();
            ex.close();
        });
        server.createContext("/api/v1/tasks/TRUNCATED-OK", ex -> {
            // 成功响应同样在读取响应体时中断
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, 100);
            ex.getResponseBody().write("{\"code\":0,".getBytes(StandardCharsets.UTF_8));
            ex.getResponseBody().flush();
            ex.close();
        });
        server.createContext("/api/v1/tasks/GATEWAY", ex -> {
            byte[] body = "<html>Bad Gateway</html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html");
            ex.sendResponseHeaders(502, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    private void json(int status, String path, String contentType, String payload) {
        server.createContext(path, ex -> {
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            if (contentType != null) {
                ex.getResponseHeaders().set("Content-Type", contentType);
            }
            ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void buildsRequestWithPathQueryBodyAndApiKey() {
        ForecastTaskClient client = ForecastTaskClients.create(RestClient.builder(), baseUrl(), "k-123");
        ForecastTaskRequest request = new ForecastTaskRequest();
        request.setRequestId("req-1");

        R<TaskSubmitResult> result = client.submit("LOAD", "full", 30, request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getTaskId()).isEqualTo("FT1");
        assertThat(captured).containsEntry("method", "POST")
                .containsEntry("query", "waitSeconds=30")
                .containsEntry("apiKey", "k-123");
        assertThat(captured.get("body")).contains("\"requestId\":\"req-1\"");
    }

    @Test
    void jsonErrorResponsesAreReturnedAsBusinessResult() {
        ForecastTaskClient client = ForecastTaskClients.create(RestClient.builder(), baseUrl());
        R<?> result = client.getTask("LIMITED");
        assertThat(result.getCode()).isEqualTo(10429);
        assertThat(result.getMessage()).isEqualTo("系统繁忙");
    }

    @Test
    void nonJsonErrorResponsesThrow() {
        ForecastTaskClient client = ForecastTaskClients.create(RestClient.builder(), baseUrl());
        assertThatThrownBy(() -> client.getTask("GATEWAY"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("HTTP 502");
    }

    /**
     * 非服务端统一结构的错误（网关/代理/Boot 默认错误页、空体、code 缺失或为 0、code 非数字、非 JSON 响应体）
     * 一律抛 ForecastClientException 并保留 HTTP 状态，绝不能被当成成功、返回 null 或抛解码异常。
     */
    @Test
    void errorsThatAreNotBusinessResultsThrowWithStatus() {
        ForecastTaskClient client = ForecastTaskClients.create(RestClient.builder(), baseUrl());
        Map<String, Integer> cases = Map.of("BOOT-ERROR", 500, "NO-CODE", 503, "EMPTY", 502,
                "ZERO-CODE", 500, "STRING-CODE", 400, "TEXT-R", 503, "NO-TYPE-R", 500);
        cases.forEach((path, status) -> assertThatThrownBy(() -> client.getTask(path))
                .as(path)
                .isInstanceOfSatisfying(ForecastClientException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(status);
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REMOTE_CALL_FAILED);
                }));
    }

    /**
     * application/*+json 的 R 同样按业务错误返回；5xx 类错误码可用 isServerError 识别，用于熔断计数。
     */
    @Test
    void jsonSuffixBusinessErrorIsReturnedAndClassifiedAsServerError() {
        ForecastTaskClient client = ForecastTaskClients.create(RestClient.builder(), baseUrl());
        R<?> engineDown = client.getTask("PROBLEM-R");
        assertThat(engineDown.getCode()).isEqualTo(ErrorCode.ENGINE_UNAVAILABLE.getCode());
        assertThat(ForecastTaskClients.isServerError(engineDown)).isTrue();
        assertThat(ForecastTaskClients.isServerError(client.getTask("LIMITED"))).isFalse();
    }

    @Test
    void isServerErrorClassifiesByErrorCodeHttpStatus() {
        assertThat(ForecastTaskClients.isServerError(R.ok())).isFalse();
        assertThat(ForecastTaskClients.isServerError(null)).isFalse();
        assertThat(ForecastTaskClients.isServerError(R.fail(ErrorCode.TASK_NOT_FOUND))).isFalse();
        assertThat(ForecastTaskClients.isServerError(R.fail(ErrorCode.TOO_MANY_REQUESTS))).isFalse();
        assertThat(ForecastTaskClients.isServerError(R.fail(ErrorCode.INTERNAL_ERROR))).isTrue();
        assertThat(ForecastTaskClients.isServerError(R.fail(ErrorCode.TASK_TIMEOUT))).isTrue();
        assertThat(ForecastTaskClients.isServerError(R.fail(ErrorCode.ENGINE_CALL_FAILED))).isTrue();
        // 未定义的错误码按服务端故障处理
        assertThat(ForecastTaskClients.isServerError(R.fail(99999, "new"))).isTrue();
    }

    @Test
    void exceptionMessageDoesNotLeakAddressOrBody() {
        ForecastTaskClient client = ForecastTaskClients.create(RestClient.builder(), baseUrl());
        assertThatThrownBy(() -> client.getTask("NO-CODE"))
                .isInstanceOfSatisfying(ForecastClientException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("预测任务服务调用失败: HTTP 503");
                    assertThat(e.getUri()).endsWith("/api/v1/tasks/NO-CODE");
                    assertThat(e.getResponseBody()).contains("no healthy upstream");
                });
    }

    /**
     * 调用方 builder 自带错误状态处理器（如 RestClientCustomizer 统一添加）时，业务错误仍以 R 返回；
     * 非 R 错误由调用方处理器优先处理。
     */
    @Test
    void consumerErrorHandlerDoesNotBreakBusinessErrors() {
        RestClient.Builder consumer = RestClient.builder().defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
            throw new IllegalArgumentException("consumer handler " + res.getStatusCode().value());
        });
        ForecastTaskClient client = ForecastTaskClients.create(consumer, baseUrl());
        assertThat(client.getTask("LIMITED").getCode()).isEqualTo(10429);
        assertThatThrownBy(() -> client.getTask("NO-CODE"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("consumer handler 503");
    }

    @Test
    void noInstancesFromLoadBalancerBecomesResourceAccessException() {
        RestClient.Builder noInstances = RestClient.builder().requestInterceptor((request, body, execution) -> {
            throw new IllegalStateException("No instances available for forecast-task-server");
        });
        assertThatThrownBy(() -> ForecastTaskClients.create(noInstances, "http://forecast-task-server").getTask("FT1"))
                .isInstanceOf(ResourceAccessException.class)
                .hasMessageContaining("No instances available for forecast-task-server")
                .hasRootCauseInstanceOf(IllegalStateException.class);

        // 引入 spring-retry 后由 RetryLoadBalancerInterceptor 调用，BlockingLoadBalancerClient 抛 IllegalArgumentException
        RestClient.Builder retryNoInstances = RestClient.builder().requestInterceptor((request, body, execution) -> {
            throw new IllegalArgumentException("Service Instance cannot be null, serviceId: forecast-task-server");
        });
        assertThatThrownBy(() -> ForecastTaskClients.create(retryNoInstances, "http://forecast-task-server").getTask("FT1"))
                .isInstanceOf(ResourceAccessException.class)
                .hasMessageContaining("Service Instance cannot be null")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);

        // 其他 IllegalStateException / IllegalArgumentException（非负载均衡无实例）不做转换
        RestClient.Builder otherBug = RestClient.builder().requestInterceptor((request, body, execution) -> {
            throw new IllegalStateException("boom");
        });
        assertThatThrownBy(() -> ForecastTaskClients.create(otherBug, baseUrl()).getTask("FT1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("boom");
        RestClient.Builder unrelatedArgBug = RestClient.builder().requestInterceptor((request, body, execution) -> {
            throw new IllegalArgumentException("bad");
        });
        assertThatThrownBy(() -> ForecastTaskClients.create(unrelatedArgBug, baseUrl()).getTask("FT1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("bad");
        RestClient.Builder wrongType = RestClient.builder().requestInterceptor((request, body, execution) -> {
            throw new IllegalStateException("Service Instance cannot be null");
        });
        assertThatThrownBy(() -> ForecastTaskClients.create(wrongType, baseUrl()).getTask("FT1"))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 读取响应体中断（错误响应与成功响应）一律为 ResourceAccessException，便于按网络异常统一重试。
     */
    @Test
    void ioErrorWhileReadingBodyIsResourceAccessException() {
        ForecastTaskClient client = ForecastTaskClients.create(RestClient.builder(), baseUrl());
        assertThatThrownBy(() -> client.getTask("TRUNCATED")).isInstanceOf(ResourceAccessException.class);
        assertThatThrownBy(() -> client.getTask("TRUNCATED-OK")).isInstanceOf(ResourceAccessException.class);
    }

    /**
     * 可观测性：非 R 错误在 http.client.requests 中保留真实状态码；业务错误按改写后的 200 记录，原状态码保留在响应头。
     */
    @Test
    void observationKeepsRealStatusForNonBusinessErrors() {
        java.util.List<Integer> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();
        io.micrometer.observation.ObservationRegistry registry = io.micrometer.observation.ObservationRegistry.create();
        registry.observationConfig().observationHandler(
                new io.micrometer.observation.ObservationHandler<org.springframework.http.client.observation.ClientRequestObservationContext>() {
                    @Override
                    public void onStop(org.springframework.http.client.observation.ClientRequestObservationContext context) {
                        try {
                            statuses.add(context.getResponse() == null ? -1 : context.getResponse().getStatusCode().value());
                        } catch (java.io.IOException e) {
                            statuses.add(-2);
                        }
                    }

                    @Override
                    public boolean supportsContext(io.micrometer.observation.Observation.Context context) {
                        return context instanceof org.springframework.http.client.observation.ClientRequestObservationContext;
                    }
                });
        ForecastTaskClient client = ForecastTaskClients.create(RestClient.builder().observationRegistry(registry), baseUrl());
        assertThatThrownBy(() -> client.getTask("NO-CODE")).isInstanceOf(ForecastClientException.class);
        assertThat(client.getTask("LIMITED").getCode()).isEqualTo(10429);
        assertThat(statuses).containsExactly(503, 200);
    }

    @Test
    void connectionFailuresSurfaceAsResourceAccessException() {
        ForecastTaskClient client = ForecastTaskClients.create(RestClient.builder(), "http://127.0.0.1:1");
        assertThatThrownBy(() -> client.getTask("FT1")).isInstanceOf(ResourceAccessException.class);
    }

    /**
     * 与负载均衡等 URI 改写类拦截器共存：外层拦截器先改写地址，业务错误拦截器仍生效。
     */
    @Test
    void worksBehindUriRewritingInterceptor() {
        RestClient.Builder lbLike = RestClient.builder().requestInterceptor((request, body, execution) ->
                execution.execute(new org.springframework.http.client.support.HttpRequestWrapper(request) {
                    @Override
                    public java.net.URI getURI() {
                        return java.net.URI.create(request.getURI().toString().replace("http://forecast-task-server", baseUrl()));
                    }
                }, body));
        ForecastTaskClient client = ForecastTaskClients.create(lbLike, "http://forecast-task-server");
        assertThat(client.getTask("LIMITED").getCode()).isEqualTo(10429);
        assertThatThrownBy(() -> client.getTask("NO-CODE")).isInstanceOf(ForecastClientException.class);
    }

    @Test
    void builderIsNotMutated() {
        RestClient.Builder shared = RestClient.builder();
        ForecastTaskClients.create(shared, baseUrl(), "k-123");
        // 原 builder 未被设置 baseUrl / API Key：用它直接请求绝对地址时不应携带鉴权头
        shared.build().post().uri(baseUrl() + "/api/v1/forecast/LOAD/full?waitSeconds=0")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body("{}")
                .retrieve().toBodilessEntity();
        assertThat(captured).containsEntry("apiKey", "null");
    }
}
