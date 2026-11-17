package com.forecast.common.enums;

/**
 * 任务触发方式。
 */
public enum TriggerType {
    /** 外部接口调用 */
    API,
    /** Cron 定时触发 */
    SCHEDULE,
    /** 定时任务手动触发 */
    MANUAL,
    /** 失败重试 */
    RETRY
}
