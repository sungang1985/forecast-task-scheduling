package com.forecast.task.data.layout;

import com.forecast.task.data.DataRow;
import com.forecast.task.data.DataSourceDefinition;
import com.forecast.task.data.DatasetLayout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 长表：一行一个时间点。
 */
@Component
public class LongTimeLayoutHandler extends AbstractJdbcLayoutHandler {

    public LongTimeLayoutHandler(@Qualifier("dataJdbcTemplate") NamedParameterJdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public DatasetLayout layout() {
        return DatasetLayout.LONG_TIME;
    }

    @Override
    public Map<String, List<DataRow>> query(DataSourceDefinition def, Collection<String> keys,
                                            LocalDateTime start, LocalDateTime end) {
        List<String> valueColumns = new ArrayList<>(def.fieldMapping().values());
        int n = valueColumns.size();
        return select(def, valueColumns, def.timeColumn(), start, end, keys, List.of(def.timeColumn()),
                (rs, key, sink) -> {
                    LocalDateTime ts = rs.getObject(def.timeColumn(), LocalDateTime.class);
                    if (ts == null) {
                        return;
                    }
                    double[] values = new double[n];
                    for (int i = 0; i < n; i++) {
                        values[i] = readDouble(rs, valueColumns.get(i));
                    }
                    sink.computeIfAbsent(key, k -> new ArrayList<>()).add(new DataRow(ts, values));
                });
    }
}
