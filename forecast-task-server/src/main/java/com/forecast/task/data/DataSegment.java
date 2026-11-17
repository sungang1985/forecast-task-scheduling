package com.forecast.task.data;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 一个缓存分段：某数据源在一个时间单元内、一个（或全部）预测对象的数据。
 *
 * @param fields 字段名（与 {@link DataRow#values()} 对齐）
 * @param series 预测对象 -> 按时间升序排列的数据行
 */
public record DataSegment(List<String> fields, Map<String, List<DataRow>> series) {

    public static DataSegment empty(List<String> fields) {
        return new DataSegment(fields, Collections.emptyMap());
    }

    public int rowCount() {
        int count = 0;
        for (List<DataRow> rows : series.values()) {
            count += rows.size();
        }
        return count;
    }
}
