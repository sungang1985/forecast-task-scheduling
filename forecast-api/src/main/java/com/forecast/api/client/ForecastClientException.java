package com.forecast.api.client;

import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import lombok.Getter;

/**
 * 调用预测任务服务失败，且响应不是服务端统一结构 R（如网关/代理返回的错误页、上游超时、空响应体）。
 * 服务端的业务错误不会抛出本异常，而是以 code != 0 的 R 返回。
 */
@Getter
public class ForecastClientException extends BizException {

    /** HTTP 状态码 */
    private final int httpStatus;
    /** 响应体片段（便于排查，最多 500 字符） */
    private final String responseBody;

    public ForecastClientException(int httpStatus, String uri, String responseBody) {
        super(ErrorCode.REMOTE_CALL_FAILED, "预测任务服务调用失败: HTTP " + httpStatus + " " + uri
                + (responseBody == null || responseBody.isEmpty() ? "" : " " + responseBody));
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
    }
}
