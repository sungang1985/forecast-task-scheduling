package com.forecast.task.data;

/**
 * 业务表结构布局。不同布局由对应的 {@link com.forecast.task.data.layout.LayoutHandler} 转换为统一时序数据。
 */
public enum DatasetLayout {
    /** 长表：一行一个时间点，time_column 为 DATETIME */
    LONG_TIME,
    /** 日期 + 时段序号：date_column + period_column(1..pointsPerDay) */
    DATE_PERIOD,
    /** 宽表：一行一天，按列存放每日 N 个点（如 p001..p096） */
    WIDE_POINTS
}
