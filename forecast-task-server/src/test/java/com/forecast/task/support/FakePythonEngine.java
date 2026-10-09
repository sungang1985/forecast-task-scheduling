package com.forecast.task.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;

/**
 * 测试用的 Python 引擎替身：实现与真实引擎相同的 HTTP 协议（分片校验、异步任务、结果分页），
 * 任务立即完成，用于验证 Java 侧的流程编排、分批传输与容错逻辑。
 */
public class FakePythonEngine {

    private static final Pattern CHUNK = Pattern.compile("^/api/v1/datasets/([^/]+)/parts/([^/]+)/chunks/(\\d+)$");
    private static final Pattern COMPLETE = Pattern.compile("^/api/v1/datasets/([^/]+)/complete$");
    private static final Pattern DATASET = Pattern.compile("^/api/v1/datasets/([^/]+)$");
    private static final Pattern TASK = Pattern.compile("^/api/v1/tasks/([^/]+)$");
    private static final Pattern CANCEL = Pattern.compile("^/api/v1/tasks/([^/]+)/cancel$");
    private static final Pattern PREDICTION = Pattern.compile("^/api/v1/predictions/([^/]+)$");

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;

    /** datasetId -> part -> 已接收行数 */
    private final Map<String, Map<String, AtomicInteger>> datasetRows = new ConcurrentHashMap<>();
    /** datasetId -> primary 中出现的 series_key */
    private final Map<String, Set<String>> datasetSeries = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> tasks = new ConcurrentHashMap<>();
    private final Map<String, List<String>> modelTargets = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> predictions = new ConcurrentHashMap<>();
    private final Set<String> deletedDatasets = ConcurrentHashMap.newKeySet();

    public final AtomicInteger chunksReceived = new AtomicInteger();
    public final AtomicInteger failTrainSubmits = new AtomicInteger();
    public final AtomicBoolean failPredictTasks = new AtomicBoolean();

    private FakePythonEngine(HttpServer server) {
        this.server = server;
    }

    public static FakePythonEngine start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakePythonEngine engine = new FakePythonEngine(server);
            server.createContext("/", engine::handle);
            server.setExecutor(Executors.newFixedThreadPool(8));
            server.start();
            return engine;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    public int rowsReceived(String datasetId, String part) {
        return datasetRows.getOrDefault(datasetId, Map.of()).getOrDefault(part, new AtomicInteger()).get();
    }

    public boolean isDeleted(String datasetId) {
        return deletedDatasets.contains(datasetId);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            Matcher m;
            if (path.equals("/health")) {
                json(ex, 200, Map.of("status", "UP", "version", "fake", "workers", 1, "running_tasks", 0, "queued_tasks", 0));
            } else if (method.equals("POST") && path.equals("/api/v1/datasets")) {
                JsonNode body = mapper.readTree(ex.getRequestBody());
                String id = "ds_" + UUID.randomUUID().toString().replace("-", "");
                Map<String, AtomicInteger> parts = new ConcurrentHashMap<>();
                body.get("parts").fieldNames().forEachRemaining(p -> parts.put(p, new AtomicInteger()));
                datasetRows.put(id, parts);
                datasetSeries.put(id, ConcurrentHashMap.newKeySet());
                json(ex, 200, Map.of("dataset_id", id));
            } else if (method.equals("PUT") && (m = CHUNK.matcher(path)).matches()) {
                uploadChunk(ex, m.group(1), m.group(2));
            } else if (method.equals("POST") && (m = COMPLETE.matcher(path)).matches()) {
                Map<String, Object> rows = new LinkedHashMap<>();
                datasetRows.get(m.group(1)).forEach((k, v) -> rows.put(k, v.get()));
                json(ex, 200, Map.of("dataset_id", m.group(1), "rows", rows, "missing_chunks", Map.of()));
            } else if (method.equals("DELETE") && (m = DATASET.matcher(path)).matches()) {
                deletedDatasets.add(m.group(1));
                ex.sendResponseHeaders(204, -1);
            } else if (method.equals("POST") && path.equals("/api/v1/preprocess")) {
                JsonNode body = mapper.readTree(ex.getRequestBody());
                json(ex, 200, submit(body, "PREPROCESS", Map.of("processed_dataset_id", "pd_" + body.get("dataset_id").asText().substring(3),
                        "rows", 1, "stats", Map.of("missing_filled", 0))));
            } else if (method.equals("POST") && path.equals("/api/v1/train")) {
                if (failTrainSubmits.getAndUpdate(v -> Math.max(0, v - 1)) > 0) {
                    json(ex, 503, Map.of("detail", "engine busy"));
                    return;
                }
                JsonNode body = mapper.readTree(ex.getRequestBody());
                String modelId = "md_" + UUID.randomUUID().toString().replace("-", "");
                List<String> targets = new ArrayList<>();
                body.get("targets").forEach(t -> targets.add(t.asText()));
                modelTargets.put(modelId, targets);
                json(ex, 200, submit(body, "TRAIN", Map.of("model_id", modelId, "train_rows", 1000,
                        "metrics", Map.of("overall", Map.of("load", Map.of("mape", 1.5))))));
            } else if (method.equals("POST") && path.equals("/api/v1/predict")) {
                predict(ex);
            } else if (method.equals("GET") && (m = TASK.matcher(path)).matches()) {
                Map<String, Object> task = tasks.get(m.group(1));
                if (task == null) {
                    json(ex, 404, Map.of("detail", "not found"));
                } else {
                    json(ex, 200, task);
                }
            } else if (method.equals("POST") && (m = CANCEL.matcher(path)).matches()) {
                json(ex, 200, Map.of("task_id", m.group(1), "status", "CANCELLED"));
            } else if (method.equals("GET") && (m = PREDICTION.matcher(path)).matches()) {
                List<Map<String, Object>> rows = predictions.get(m.group(1));
                Map<String, String> query = query(ex.getRequestURI());
                int offset = Integer.parseInt(query.getOrDefault("offset", "0"));
                int limit = Integer.parseInt(query.getOrDefault("limit", "5000"));
                List<Map<String, Object>> page = rows.subList(Math.min(offset, rows.size()), Math.min(rows.size(), offset + limit));
                json(ex, 200, Map.of("prediction_id", m.group(1), "total", rows.size(), "offset", offset, "rows", page));
            } else {
                json(ex, 404, Map.of("detail", "unknown path " + path));
            }
        } catch (Exception e) {
            json(ex, 500, Map.of("detail", String.valueOf(e)));
        } finally {
            ex.close();
        }
    }

    private void uploadChunk(HttpExchange ex, String datasetId, String part) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        CRC32 crc = new CRC32();
        crc.update(body);
        String expected = ex.getRequestHeaders().getFirst("X-Chunk-Checksum");
        if (!Long.toHexString(crc.getValue()).equals(expected)) {
            json(ex, 422, Map.of("detail", "checksum mismatch"));
            return;
        }
        InputStream in = "gzip".equals(ex.getRequestHeaders().getFirst("X-Payload-Encoding"))
                ? new GZIPInputStream(new ByteArrayInputStream(body)) : new ByteArrayInputStream(body);
        JsonNode payload = mapper.readTree(in);
        int rows = payload.get("rows").size();
        if (rows != Integer.parseInt(ex.getRequestHeaders().getFirst("X-Chunk-Rows"))) {
            json(ex, 400, Map.of("detail", "row count mismatch"));
            return;
        }
        if ("primary".equals(part)) {
            payload.get("rows").forEach(r -> datasetSeries.get(datasetId).add(r.get(1).asText()));
        }
        datasetRows.get(datasetId).get(part).addAndGet(rows);
        chunksReceived.incrementAndGet();
        ex.sendResponseHeaders(204, -1);
    }

    private void predict(HttpExchange ex) throws IOException {
        JsonNode body = mapper.readTree(ex.getRequestBody());
        if (failPredictTasks.get()) {
            String id = "tk_" + UUID.randomUUID().toString().replace("-", "");
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("task_id", id);
            task.put("type", "PREDICT");
            task.put("status", "FAILED");
            task.put("progress", 0);
            task.put("error", "ValueError: model input mismatch");
            tasks.put(id, task);
            json(ex, 200, Map.of("task_id", id, "status", "PENDING"));
            return;
        }
        LocalDateTime start = LocalDateTime.parse(body.get("forecast_start").asText());
        LocalDateTime end = LocalDateTime.parse(body.get("forecast_end").asText());
        List<String> targets = modelTargets.getOrDefault(body.get("model_id").asText(), List.of("load"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String key : datasetSeries.getOrDefault(body.get("dataset_id").asText(), Set.of())) {
            for (String target : targets) {
                for (LocalDateTime t = start; t.isBefore(end); t = t.plusMinutes(15)) {
                    rows.add(Map.of("series_key", key, "target", target, "ts", t.toString(),
                            "value", 100.0, "lower", 90.0, "upper", 110.0));
                }
            }
        }
        String predictionId = "pr_" + UUID.randomUUID().toString().replace("-", "");
        predictions.put(predictionId, rows);
        json(ex, 200, submit(body, "PREDICT", Map.of("prediction_id", predictionId, "rows", rows.size())));
    }

    private Map<String, Object> submit(JsonNode body, String type, Map<String, Object> result) {
        String id = "tk_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("task_id", id);
        task.put("type", type);
        task.put("status", "SUCCESS");
        task.put("progress", 100);
        task.put("result", result);
        tasks.put(id, task);
        return Map.of("task_id", id, "status", "PENDING");
    }

    private void json(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                String[] kv = pair.split("=", 2);
                result.put(kv[0], kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        return result;
    }
}
