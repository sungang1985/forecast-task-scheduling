package com.forecast.task.data;

import java.time.LocalDateTime;

/**
 * 统一时序数据行：时间点 + 与字段列表对齐的数值（缺失值为 NaN）。
 * 使用原始 double 数组以降低大批量数据的内存占用。
 */
public record DataRow(LocalDateTime ts, double[] values) {
}
