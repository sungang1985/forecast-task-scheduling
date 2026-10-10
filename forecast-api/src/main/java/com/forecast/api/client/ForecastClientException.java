package com.forecast.api.client;

import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import lombok.Getter;

/**
 * 调用预测任务服务得到错误响应，且响应不是服务端统一结构 R（如网关/代理返回的错误页、Spring Boot 默认错误 JSON、空响应体）。
 * 服务端的业务错误不会抛出本异常，而是以 code != 0 的 R 返回。
 * <p>
 * {@link #getMessage()} 只包含 HTTP 状态码，可安全地透传给上游调用方；请求地址（可能是负载均衡解析出的内部实例地址）
 * 与响应体片段请通过 {@link #getUri()}、{@link #getResponseBody()} 获取并仅用于日志排查。
 */
@Getter
public class ForecastClientException extends BizException {

    /** HTTP 状态码 */
    private final int httpStatus;
    /** 请求地址（仅用于日志） */
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
