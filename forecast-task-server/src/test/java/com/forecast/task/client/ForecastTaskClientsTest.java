package com.forecast.task.client;

import com.forecast.api.client.ForecastTaskClient;
import com.forecast.api.client.ForecastTaskClients;
import com.forecast.api.dto.ForecastTaskRequest;
import com.forecast.api.dto.TaskSubmitResult;
import com.forecast.common.exception.BizException;
import com.forecast.common.model.R;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        server.createContext("/api/v1/tasks/GATEWAY", ex -> {
            byte[] body = "<html>Bad Gateway</html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html");
            ex.sendResponseHeaders(502, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
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
