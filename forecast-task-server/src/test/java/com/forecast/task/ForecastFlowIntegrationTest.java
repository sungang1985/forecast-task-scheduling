package com.forecast.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.forecast.common.enums.TriggerType;
import com.forecast.task.data.cache.SegmentCache;
import com.forecast.task.domain.entity.JobConfig;
import com.forecast.task.domain.entity.JobFireLog;
import com.forecast.task.mapper.JobFireLogMapper;
import com.forecast.task.schedule.JobConfigService;
import com.forecast.task.schedule.JobFireService;
import com.forecast.task.support.FakePythonEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 集成测试：真实 Spring 上下文 + H2（含演示表/演示数据）+ 协议一致的 Python 引擎替身。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ForecastFlowIntegrationTest {

    static final FakePythonEngine ENGINE = FakePythonEngine.start();

    @DynamicPropertySource
    static void engineProperties(DynamicPropertyRegistry registry) {
        registry.add("forecast.engine.base-url", ENGINE::baseUrl);
    }

    @AfterAll
    static void stopEngine() {
        ENGINE.stop();
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    SegmentCache segmentCache;
    @Autowired
    JobConfigService jobConfigService;
    @Autowired
    JobFireService jobFireService;
    @Autowired
    JobFireLogMapper fireLogMapper;

    private static Map<String, Object> loadRequest(String requestId) {
        LocalDate today = LocalDate.now();
        return Map.of("requestId", requestId,
                "seriesKeys", List.of("AREA_01", "AREA_02"),
                "historyStart", today.minusDays(30).atStartOfDay().toString(),
                "historyEnd", today.atStartOfDay().toString());
    }

    private JsonNode post(String url, Object body) {
        ResponseEntity<JsonNode> resp = rest.postForEntity(url, body, JsonNode.class);
        return resp.getBody();
    }

    @Test
    void fullLoadForecastRunsAllStepsTransfersInChunksAndPersistsResults() {
        int chunksBefore = ENGINE.chunksReceived.get();
        JsonNode resp = post("/api/v1/forecast/LOAD/full?waitSeconds=60", loadRequest("it-full-1"));

        assertThat(resp.get("code").asInt()).isZero();
        JsonNode detail = resp.get("data").get("detail");
        assertThat(detail.get("status").asText()).isEqualTo("SUCCESS");
        List<String> steps = new ArrayList<>();
        detail.get("steps").forEach(s -> steps.add(s.get("stepName").asText() + ":" + s.get("status").asText()));
        assertThat(steps).containsExactly("LOAD_DATA:SUCCESS", "PREPROCESS:SUCCESS", "TRAIN:SUCCESS",
                "PREDICT:SUCCESS", "PERSIST_RESULT:SUCCESS");

        JsonNode summary = detail.get("resultSummary");
        // 2 个区域 × 30 天 × 96 点（演示数据中少量缺测点不输出）
        int primaryRows = summary.get("dataRows").get("primary").asInt();
        assertThat(primaryRows).isBetween(2 * 30 * 96 - 20, 2 * 30 * 96);
        // chunk-rows=1000 => 主数据至少分 5 片
        assertThat(ENGINE.chunksReceived.get() - chunksBefore).isGreaterThanOrEqualTo(6);
        assertThat(summary.get("resultRows").asInt()).isEqualTo(2 * 96);

        String datasetId = detail.get("steps").get(0).get("output").get("datasetId").asText();
        assertThat(ENGINE.rowsReceived(datasetId, "primary")).isEqualTo(primaryRows);
        assertThat(ENGINE.isDeleted(datasetId)).as("任务结束后清理引擎临时数据集").isTrue();

        // 配置中的列表参数原样透传（而不是被绑定成 {"0":96,"1":672}）
        assertThat(detail.get("request").get("params").get("lags").toString()).isEqualTo("[96,672]");

        String taskId = detail.get("taskId").asText();
        JsonNode results = rest.getForObject("/api/v1/tasks/" + taskId + "/results?size=50", JsonNode.class);
        assertThat(results.get("data").get("total").asInt()).isEqualTo(192);
        assertThat(results.get("data").get("records").get(0).get("forecastValue").asDouble()).isEqualTo(100.0);

        // 训练产出的模型已登记，可直接用于 PREDICT 模式
        JsonNode predict = post("/api/v1/forecast/LOAD/predict?waitSeconds=60", Map.of("seriesKeys", List.of("AREA_01")));
        assertThat(predict.get("data").get("status").asText()).isEqualTo("SUCCESS");
        assertThat(predict.get("data").get("detail").get("resultSummary").get("resultRows").asInt()).isEqualTo(96);
    }

    @Test
    void repeatedWindowIsServedFromCacheAndRequestIdIsIdempotent() {
        post("/api/v1/forecast/LOAD/preprocess?waitSeconds=60", loadRequest("it-cache-1"));
        long loadsBefore = ((Number) segmentCache.stats().get("dbLoadedSegments")).longValue();

        JsonNode second = post("/api/v1/forecast/LOAD/preprocess?waitSeconds=60", loadRequest("it-cache-2"));
        assertThat(second.get("data").get("status").asText()).isEqualTo("SUCCESS");
        long loadsAfter = ((Number) segmentCache.stats().get("dbLoadedSegments")).longValue();
        assertThat(loadsAfter).as("相同时间窗口不再读库").isEqualTo(loadsBefore);

        JsonNode dup = post("/api/v1/forecast/LOAD/preprocess", loadRequest("it-cache-2"));
        assertThat(dup.get("data").get("duplicated").asBoolean()).isTrue();
        assertThat(dup.get("data").get("taskId").asText()).isEqualTo(second.get("data").get("taskId").asText());
    }

    @Test
    void transientEngineErrorsAreRetried() {
        ENGINE.failTrainSubmits.set(2);
        JsonNode resp = post("/api/v1/forecast/LOAD/train?waitSeconds=60", loadRequest("it-retry-1"));
        assertThat(resp.get("data").get("status").asText()).isEqualTo("SUCCESS");
        assertThat(ENGINE.failTrainSubmits.get()).isZero();
    }

    @Test
    void engineTaskFailureFailsTaskWithoutRetry() {
        ENGINE.failPredictTasks.set(true);
        try {
            JsonNode resp = post("/api/v1/forecast/PRICE/full?waitSeconds=60", Map.of("seriesKeys", List.of("MARKET_A"),
                    "historyStart", LocalDate.now().minusDays(20).atStartOfDay().toString()));
            JsonNode detail = resp.get("data").get("detail");
            assertThat(detail.get("status").asText()).isEqualTo("FAILED");
            assertThat(detail.get("errorCode").asText()).isEqualTo("ENGINE_TASK_FAILED");
            JsonNode lastStep = detail.get("steps").get(detail.get("steps").size() - 1);
            assertThat(lastStep.get("stepName").asText()).isEqualTo("PREDICT");
            assertThat(lastStep.get("attempt").asInt()).isEqualTo(1);
        } finally {
            ENGINE.failPredictTasks.set(false);
        }
    }

    @Test
    void validationErrorsReturnBusinessCodes() {
        ResponseEntity<JsonNode> unknownType = rest.postForEntity("/api/v1/forecast/WIND/full", Map.of(), JsonNode.class);
        assertThat(unknownType.getStatusCode().value()).isEqualTo(400);
        assertThat(unknownType.getBody().get("code").asInt()).isEqualTo(20400);

        ResponseEntity<JsonNode> noModel = rest.postForEntity("/api/v1/forecast/LOAD/predict",
                Map.of("seriesKeys", List.of("AREA_99")), JsonNode.class);
        assertThat(noModel.getStatusCode().value()).isEqualTo(404);
        assertThat(noModel.getBody().get("code").asInt()).isEqualTo(40404);
    }

    @Test
    void callbackIsPostedWhenTaskFinishes() throws Exception {
        java.util.concurrent.BlockingQueue<String> received = new java.util.concurrent.LinkedBlockingQueue<>();
        com.sun.net.httpserver.HttpServer receiver = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/callback", ex -> {
            received.add(new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        receiver.start();
        try {
            String url = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/callback";
            Map<String, Object> body = new java.util.HashMap<>(loadRequest("it-callback-1"));
            body.put("callbackUrl", url);
            String taskId = post("/api/v1/forecast/LOAD/preprocess", body).get("data").get("taskId").asText();

            String payload = received.poll(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(payload).isNotNull().contains(taskId).contains("\"status\":\"SUCCESS\"");
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void scheduledFireIsDeduplicatedAndResultWrittenBack() {
        JobConfig job = jobConfigService.listAll().stream()
                .filter(j -> "LOAD_DAY_AHEAD".equals(j.getJobCode())).findFirst().orElseThrow();
        ZonedDateTime fireTime = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(ChronoUnit.MINUTES);

        Optional<JobFireLog> first = jobFireService.fire(job, fireTime, 0, TriggerType.SCHEDULE, null);
        // 模拟集群中另一个节点在同一触发点触发
        Optional<JobFireLog> second = jobFireService.fire(job, fireTime, 0, TriggerType.SCHEDULE, null);

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        String taskId = first.get().getTaskId();
        assertThat(taskId).isNotBlank();

        await().atMost(java.time.Duration.ofSeconds(60)).untilAsserted(() -> {
            JobFireLog log = fireLogMapper.selectOne(Wrappers.<JobFireLog>lambdaQuery().eq(JobFireLog::getTaskId, taskId));
            assertThat(log.getStatus()).isEqualTo("SUCCESS");
            assertThat(jobConfigService.require(job.getId()).getLastStatus()).isEqualTo("SUCCESS");
        });
        JsonNode task = rest.getForObject("/api/v1/tasks/" + taskId, JsonNode.class).get("data");
        assertThat(task.get("triggerType").asText()).isEqualTo("SCHEDULE");
        assertThat(task.get("jobId").asText()).isEqualTo(String.valueOf(job.getId()));
        assertThat(task.get("resultSummary").get("resultRows").asInt()).isEqualTo(3 * 96);
    }
}
