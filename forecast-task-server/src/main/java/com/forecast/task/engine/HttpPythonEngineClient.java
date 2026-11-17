package com.forecast.task.engine;

import com.forecast.common.exception.ErrorCode;
import com.forecast.task.engine.dto.EngineDtos.CompleteDatasetRequest;
import com.forecast.task.engine.dto.EngineDtos.CompleteDatasetResponse;
import com.forecast.task.engine.dto.EngineDtos.CreateDatasetRequest;
import com.forecast.task.engine.dto.EngineDtos.CreateDatasetResponse;
import com.forecast.task.engine.dto.EngineDtos.EngineHealth;
import com.forecast.task.engine.dto.EngineDtos.EngineTaskRef;
import com.forecast.task.engine.dto.EngineDtos.EngineTaskStatus;
import com.forecast.task.engine.dto.EngineDtos.PredictRequest;
import com.forecast.task.engine.dto.EngineDtos.PredictionPage;
import com.forecast.task.engine.dto.EngineDtos.PreprocessRequest;
import com.forecast.task.engine.dto.EngineDtos.TrainRequest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Supplier;

/**
 * 基于 HTTP 的 Python 引擎客户端。
 * <p>
 * 所有提交类接口都携带 requestId，Python 侧按 requestId 去重，因此可以安全重试。
 */
@Slf4j
@Component
public class HttpPythonEngineClient implements PythonEngineClient {

    private static final String API = "/api/v1";

    private final RestClient restClient;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final MeterRegistry meterRegistry;

    public HttpPythonEngineClient(RestClient engineRestClient, Retry engineRetry,
                                  CircuitBreaker engineCircuitBreaker, MeterRegistry meterRegistry) {
        this.restClient = engineRestClient;
        this.retry = engineRetry;
        this.circuitBreaker = engineCircuitBreaker;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public EngineHealth health(EngineEndpoint endpoint) {
        // 健康检查不重试、不计入熔断
        return translate("health", () -> restClient.get().uri(endpoint.url("/health"))
                .retrieve().body(EngineHealth.class));
    }

    @Override
    public String createDataset(EngineEndpoint endpoint, CreateDatasetRequest request) {
        CreateDatasetResponse response = call("create_dataset", () -> restClient.post()
                .uri(endpoint.url(API + "/datasets"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve().body(CreateDatasetResponse.class));
        return response.datasetId();
    }

    @Override
    public void uploadChunk(EngineEndpoint endpoint, String datasetId, String part, int seq, byte[] body,
                            boolean gzip, int rows, String checksum) {
        call("upload_chunk", () -> restClient.put()
                .uri(endpoint.url(API + "/datasets/{datasetId}/parts/{part}/chunks/{seq}"), datasetId, part, seq)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Payload-Encoding", gzip ? "gzip" : "identity")
                .header("X-Chunk-Rows", String.valueOf(rows))
                .header("X-Chunk-Checksum", checksum)
                .body(body)
                .retrieve().toBodilessEntity());
    }

    @Override
    public CompleteDatasetResponse completeDataset(EngineEndpoint endpoint, String datasetId,
                                                   CompleteDatasetRequest request) {
        return call("complete_dataset", () -> restClient.post()
                .uri(endpoint.url(API + "/datasets/{datasetId}/complete"), datasetId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve().body(CompleteDatasetResponse.class));
    }

    @Override
    public void deleteDataset(EngineEndpoint endpoint, String datasetId) {
        call("delete_dataset", () -> restClient.delete()
                .uri(endpoint.url(API + "/datasets/{datasetId}"), datasetId)
                .retrieve().toBodilessEntity());
    }

    @Override
    public EngineTaskRef submitPreprocess(EngineEndpoint endpoint, PreprocessRequest request) {
        return post("submit_preprocess", endpoint, "/preprocess", request);
    }

    @Override
    public EngineTaskRef submitTrain(EngineEndpoint endpoint, TrainRequest request) {
        return post("submit_train", endpoint, "/train", request);
    }

    @Override
    public EngineTaskRef submitPredict(EngineEndpoint endpoint, PredictRequest request) {
        return post("submit_predict", endpoint, "/predict", request);
    }

    @Override
    public EngineTaskStatus getTask(EngineEndpoint endpoint, String engineTaskId) {
        return call("get_task", () -> restClient.get()
                .uri(endpoint.url(API + "/tasks/{taskId}"), engineTaskId)
                .retrieve().body(EngineTaskStatus.class));
    }

    @Override
    public void cancelTask(EngineEndpoint endpoint, String engineTaskId) {
        call("cancel_task", () -> restClient.post()
                .uri(endpoint.url(API + "/tasks/{taskId}/cancel"), engineTaskId)
                .retrieve().toBodilessEntity());
    }

    @Override
    public PredictionPage getPredictions(EngineEndpoint endpoint, String predictionId, int offset, int limit) {
        return call("get_predictions", () -> restClient.get()
                .uri(endpoint.url(API + "/predictions/{id}?offset={offset}&limit={limit}"), predictionId, offset, limit)
                .retrieve().body(PredictionPage.class));
    }

    private EngineTaskRef post(String operation, EngineEndpoint endpoint, String path, Object body) {
        return call(operation, () -> restClient.post()
                .uri(endpoint.url(API + path))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(EngineTaskRef.class));
    }

    /**
     * 统一调用入口：异常翻译 -> 熔断 -> 重试 -> 指标。
     */
    private <T> T call(String operation, Supplier<T> supplier) {
        Supplier<T> guarded = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, () -> translate(operation, supplier)));
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            return guarded.get();
        } catch (CallNotPermittedException e) {
            outcome = "circuit_open";
            throw new EngineCallException(ErrorCode.ENGINE_UNAVAILABLE,
                    "Python 引擎熔断中，暂停调用: " + operation, false, 503, e);
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            sample.stop(Timer.builder("forecast.engine.calls")
                    .description("Python 引擎调用耗时")
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .register(meterRegistry));
        }
    }

    private static <T> T translate(String operation, Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            boolean retryable = status >= 500 || status == 429 || status == 408;
            String body = e.getResponseBodyAsString();
            if (body.length() > 500) {
                body = body.substring(0, 500);
            }
            ErrorCode code = status == 404 ? ErrorCode.NOT_FOUND : ErrorCode.ENGINE_CALL_FAILED;
            throw new EngineCallException(code,
                    "Python 引擎调用失败 [" + operation + "] HTTP " + status + ": " + body, retryable, status, e);
        } catch (ResourceAccessException e) {
            throw new EngineCallException(ErrorCode.ENGINE_UNAVAILABLE,
                    "Python 引擎网络异常 [" + operation + "]: " + e.getMostSpecificCause().getMessage(), true, 0, e);
        } catch (RestClientException e) {
            throw new EngineCallException(ErrorCode.ENGINE_CALL_FAILED,
                    "Python 引擎响应解析失败 [" + operation + "]: " + e.getMessage(), false, 0, e);
        }
    }
}
