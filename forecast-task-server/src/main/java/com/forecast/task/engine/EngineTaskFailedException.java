package com.forecast.task.engine;

import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import lombok.Getter;

/**
 * Python 引擎异步任务执行失败（算法/数据问题，不做自动重试）。
 */
@Getter
public class EngineTaskFailedException extends BizException {

    private final String engineTaskId;

    public EngineTaskFailedException(String engineTaskId, String message) {
        super(ErrorCode.ENGINE_TASK_FAILED, message);
        this.engineTaskId = engineTaskId;
    }
}
