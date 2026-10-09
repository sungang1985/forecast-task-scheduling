package com.forecast.task.data;

import java.util.List;

/**
 * 按时间单元流式输出的一段序列数据（已裁剪到请求时间窗口）。
 */
public record SeriesSlice(String seriesKey, List<String> fields, List<DataRow> rows) {
}
