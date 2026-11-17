package com.forecast.task.engine.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 与 Python 预测引擎交互的协议对象。HTTP 序列化统一使用 snake_case（见 EngineClientConfig）。
 * 协议说明见 forecast-python-engine/README.md。
 */
public final class EngineDtos {

    private EngineDtos() {
    }

    /** 数据集分区描述：primary 为预测目标数据，其余为外生变量 */
    public record PartSpec(String source, String role, List<String> columns, boolean joinOnKey) {
    }

    public record CreateDatasetRequest(String taskId, String taskType, String frequency, Map<String, PartSpec> parts) {
    }

    public record CreateDatasetResponse(String datasetId) {
    }

    public record PartSummary(int chunks, long rows) {
    }

    public record CompleteDatasetRequest(Map<String, PartSummary> parts) {
    }

    public record CompleteDatasetResponse(String datasetId, Map<String, Long> rows,
                                          Map<String, List<Integer>> missingChunks) {
    }

    public record PreprocessRequest(String requestId, String taskId, String taskType, String datasetId,
                                    List<String> targets, String frequency, Map<String, Object> params) {
    }

    public record TrainRequest(String requestId, String taskId, String taskType, String processedDatasetId,
                               List<String> targets, String algorithm, Map<String, Object> params) {
    }

    public record PredictRequest(String requestId, String taskId, String taskType, String modelId,
                                 String datasetId, LocalDateTime forecastStart, LocalDateTime forecastEnd,
                                 Map<String, Object> params) {
    }

    /** 异步任务提交回执 */
    public record EngineTaskRef(String taskId, String status) {
    }

    /** 异步任务状态：status ∈ PENDING/RUNNING/SUCCESS/FAILED/CANCELLED */
    public record EngineTaskStatus(String taskId, String type, String status, Integer progress, String message,
                                   Map<String, Object> result, String error) {

        public boolean isTerminal() {
            return "SUCCESS".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
        }

        public boolean isSuccess() {
            return "SUCCESS".equals(status);
        }
    }

    public record PredictionRow(String seriesKey, String target, LocalDateTime ts, Double value,
                                Double lower, Double upper) {
    }

    public record PredictionPage(String predictionId, long total, int offset, List<PredictionRow> rows) {
    }

    public record EngineHealth(String status, String version, Integer workers, Integer runningTasks,
                               Integer queuedTasks) {
    }
}
