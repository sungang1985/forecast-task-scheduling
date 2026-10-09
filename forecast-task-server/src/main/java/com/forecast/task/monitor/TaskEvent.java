package com.forecast.task.monitor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 任务生命周期事件（Spring 应用事件）。监听方：SSE 实时推送、定时任务结果回写、回调通知、指标统计等。
 * 新增监控/告警能力时监听本事件即可，无需侵入执行流程。
 */
public record TaskEvent(String taskId, String taskType, String mode, Type type, String step, Integer progress,
                        String status, String message, Long jobId, Map<String, Object> data,
                        LocalDateTime time) {

    public enum Type {
        SUBMITTED, STARTED, STEP_STARTED, STEP_PROGRESS, STEP_FINISHED, FINISHED
    }

    public static TaskEvent of(String taskId, String taskType, String mode, Type type, String step,
                               Integer progress, String status, String message, Long jobId) {
        return new TaskEvent(taskId, taskType, mode, type, step, progress, status, message, jobId, Map.of(),
                LocalDateTime.now());
    }

    public boolean isFinished() {
        return type == Type.FINISHED;
    }
}
