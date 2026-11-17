package com.forecast.task.service;

import com.forecast.common.enums.TriggerType;

import java.time.LocalDateTime;

/**
 * 任务提交的附加信息（触发来源）。
 */
public record SubmitOptions(TriggerType triggerType, Long jobId, LocalDateTime jobFireTime, String parentTaskId,
                            int retryCount) {

    public static SubmitOptions api() {
        return new SubmitOptions(TriggerType.API, null, null, null, 0);
    }

    public static SubmitOptions retryOf(String parentTaskId, int retryCount, Long jobId, LocalDateTime fireTime) {
        return new SubmitOptions(TriggerType.RETRY, jobId, fireTime, parentTaskId, retryCount);
    }
}
