package com.forecast.task.pipeline;

/**
 * 预测流程步骤（扩展点）。
 * <p>
 * 新增一种预测流程时：实现本接口并注册为 Spring Bean，然后在配置
 * {@code forecast.pipelines.<流程名>.<模式>} 中按顺序引用步骤名即可。
 * 步骤应尽量幂等，以便失败时由执行器自动重试。
 */
public interface ForecastStep {

    /** 步骤名（全局唯一，用于流程编排配置） */
    String name();

    /** 步骤说明 */
    default String description() {
        return name();
    }

    /**
     * 是否跳过：上下文已具备本步骤产物时返回 true（如请求中直接指定了 modelId 时跳过训练）。
     */
    default boolean shouldSkip(PipelineContext ctx) {
        return false;
    }

    /** 失败后是否允许执行器自动重试 */
    default boolean retryable() {
        return true;
    }

    StepResult execute(PipelineContext ctx) throws Exception;
}
