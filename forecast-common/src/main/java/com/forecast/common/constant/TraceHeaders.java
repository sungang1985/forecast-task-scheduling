package com.forecast.common.constant;

/**
 * 链路追踪及调用相关的 HTTP 头 / MDC 键。
 */
public final class TraceHeaders {

    private TraceHeaders() {
    }

    /** 业务链路 ID（与 Micrometer Tracing 的 traceId 对齐） */
    public static final String TRACE_ID = "X-Trace-Id";
    /** 预测任务 ID，透传给 Python 引擎便于日志关联 */
    public static final String TASK_ID = "X-Task-Id";
    /** 接口鉴权 API Key */
    public static final String API_KEY = "X-API-Key";

    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_TASK_ID = "taskId";
}
