package com.forecast.task.pipeline;

import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;

/**
 * 任务执行超时。
 */
public class TaskTimeoutException extends BizException {

    public TaskTimeoutException(String message) {
        super(ErrorCode.TASK_TIMEOUT, message);
    }
}
