package com.forecast.common.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * 预测任务状态。
 */
public enum TaskStatus {
    /** 已提交，等待线程池执行 */
    PENDING,
    /** 执行中 */
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED,
    TIMEOUT;

    private static final Set<TaskStatus> TERMINAL = EnumSet.of(SUCCESS, FAILED, CANCELLED, TIMEOUT);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public static Set<TaskStatus> activeStatuses() {
        return EnumSet.of(PENDING, RUNNING);
    }
}
