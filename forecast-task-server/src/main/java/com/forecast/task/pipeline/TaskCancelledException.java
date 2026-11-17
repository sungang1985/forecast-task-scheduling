package com.forecast.task.pipeline;

import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;

/**
 * 任务被取消。
 */
public class TaskCancelledException extends BizException {

    public TaskCancelledException(String message) {
        super(ErrorCode.TASK_CANCELLED, message);
    }
}
