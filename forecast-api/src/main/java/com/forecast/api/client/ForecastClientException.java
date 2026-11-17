package com.forecast.api.client;

import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import lombok.Getter;

/**
 * 调用预测任务服务得到错误响应，且响应不是服务端统一结构 R（如网关/代理返回的错误页、Spring Boot 默认错误 JSON、空响应体、
 * 非 JSON 响应体）。服务端的业务错误不会抛出本异常，而是以 code != 0 的 R 返回。
 * <p>
 * {@link #getMessage()} 只包含 HTTP 状态码，可安全地透传给上游调用方；请求地址与响应体片段请通过
 * {@link #getUri()}、{@link #getResponseBody()} 获取并仅用于日志排查。
 */
@Getter
public class ForecastClientException extends BizException {

    /** HTTP 状态码 */
    private final int httpStatus;
    /**
     * 客户端发出的请求地址（仅用于日志）。按服务名负载均衡调用时为服务名地址（如 {@code http://forecast-task-server/api/v1/...}），
     * 不是实际命中的实例地址；实例地址请结合链路追踪或负载均衡日志排查。
     */
    private final String uri;
    /** 响应体片段（仅用于日志，最多 500 字符） */
    private final String responseBody;

    public ForecastClientException(int httpStatus, String uri, String responseBody) {
        super(ErrorCode.REMOTE_CALL_FAILED, "预测任务服务调用失败: HTTP " + httpStatus);
        this.httpStatus = httpStatus;
        this.uri = uri;
        this.responseBody = responseBody;
    }
}
