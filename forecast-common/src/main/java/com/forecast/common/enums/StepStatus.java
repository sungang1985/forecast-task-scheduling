package com.forecast.common.enums;

/**
 * 流程步骤状态。
 */
public enum StepStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    /** 上下文已具备该步骤产出（如请求中已指定 modelId），跳过执行 */
    SKIPPED,
    CANCELLED
}
