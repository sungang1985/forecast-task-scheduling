package com.forecast.task.data.cache;

import java.time.LocalDate;

/**
 * 缓存分段键：数据源 + 预测对象（* 表示全部） + 分段时间范围 [unitStart, unitEnd)。
 */
public record SegmentKey(String sourceCode, String seriesKey, LocalDate unitStart, LocalDate unitEnd) {

    public static final String ALL_SERIES = "*";

    public String redisKey(String prefix) {
        return prefix + sourceCode + ":" + unitStart + ":" + unitEnd + ":" + seriesKey;
    }

    public boolean isAllSeries() {
        return ALL_SERIES.equals(seriesKey);
    }

    /** 分段与 [from, to) 是否有交集；from/to 为 null 表示不限 */
    public boolean overlaps(LocalDate from, LocalDate to) {
        boolean afterFrom = from == null || unitEnd.isAfter(from);
        boolean beforeTo = to == null || unitStart.isBefore(to);
        return afterFrom && beforeTo;
    }
}
