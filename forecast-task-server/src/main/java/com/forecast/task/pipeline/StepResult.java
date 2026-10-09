package com.forecast.task.pipeline;

import java.util.Collections;
import java.util.Map;

/**
 * 步骤执行结果。
 *
 * @param message      结果说明
 * @param output       步骤产出（写入步骤记录 output_json，便于追踪）
 * @param engineTaskId 对应的 Python 引擎任务 ID（如有）
 */
public record StepResult(String message, Map<String, Object> output, String engineTaskId) {

    public static StepResult of(String message, Map<String, Object> output) {
        return new StepResult(message, output == null ? Collections.emptyMap() : output, null);
    }

    public static StepResult of(String message, Map<String, Object> output, String engineTaskId) {
        return new StepResult(message, output == null ? Collections.emptyMap() : output, engineTaskId);
    }

    public static StepResult skipped(String reason) {
        return new StepResult(reason, Collections.emptyMap(), null);
    }
}
