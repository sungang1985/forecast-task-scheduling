package com.forecast.task.data.layout;

import com.forecast.task.data.DataRow;
import com.forecast.task.data.DataSourceDefinition;
import com.forecast.task.data.DatasetLayout;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 表结构适配器：把某种布局的业务表查询结果转换为统一的 (series_key -> [ts, values...]) 结构。
 * 新增表结构时实现本接口并注册为 Spring Bean 即可。
 */
public interface LayoutHandler {

    DatasetLayout layout();

    /**
     * 查询 [start, end) 范围内的数据。start/end 均为分段边界（整天）。
     *
     * @param keys 预测对象，为 null 表示不过滤
     * @return 预测对象 -> 数据行（无需排序，由调用方统一排序）
     */
    Map<String, List<DataRow>> query(DataSourceDefinition def, Collection<String> keys,
                                     LocalDateTime start, LocalDateTime end);
}
