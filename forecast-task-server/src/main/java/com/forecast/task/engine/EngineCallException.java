package com.forecast.task.engine;

import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import lombok.Getter;

/**
 * Python 引擎调用异常。retryable 标识是否为可重试的瞬时故障（网络异常、5xx、429）。
 */
@Getter
public class EngineCallException extends BizException {

    private final boolean retryable;
    private final int httpStatus;

    public EngineCallException(ErrorCode code, String message, boolean retryable, int httpStatus, Throwable cause) {
        super(code, message, cause);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
    }
}
