package com.forecast.task.service;

import com.forecast.task.engine.dto.EngineDtos.PredictionRow;

import java.util.List;

/**
 * 预测结果输出扩展点。默认写入 forecast_result；如需同时写入业务结果表、推送消息队列等，
 * 实现本接口并注册为 Spring Bean，PERSIST_RESULT 步骤会依次调用所有实现。
 */
public interface ResultSink {

    String name();

    /** 写入前准备（如清理该任务已写入的数据，保证重试幂等） */
    default void prepare(ResultContext context) {
    }

    /** 分页写入 */
    void write(ResultContext context, List<PredictionRow> rows);

    record ResultContext(String taskId, String taskType, Long jobId, String modelId) {
    }
}
