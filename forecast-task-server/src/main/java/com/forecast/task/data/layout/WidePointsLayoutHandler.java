package com.forecast.task.data.layout;

import com.forecast.task.data.DataRow;
import com.forecast.task.data.DataSourceDefinition;
import com.forecast.task.data.DatasetLayout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 宽表：一行一天，点 i（1..N）的列名由字段映射中的格式串生成（如 p%03d -> p001..p096），
 * 支持多个指标共存于同一宽表（如负荷 p001..p096、温度 t001..t096）。
 */
@Component
public class WidePointsLayoutHandler extends AbstractJdbcLayoutHandler {

    public WidePointsLayoutHandler(@Qualifier("dataJdbcTemplate") NamedParameterJdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public DatasetLayout layout() {
        return DatasetLayout.WIDE_POINTS;
    }

    @Override
    public Map<String, List<DataRow>> query(DataSourceDefinition def, Collection<String> keys,
                                            LocalDateTime start, LocalDateTime end) {
        int points = def.pointsPerDay();
        int minutes = def.minutesPerPoint();
        List<String> patterns = new ArrayList<>(def.fieldMapping().values());
        int fieldCount = patterns.size();
        // columns[f][p] = 第 f 个字段第 p+1 个点的列名
        String[][] columns = new String[fieldCount][points];
        List<String> allColumns = new ArrayList<>(fieldCount * points);
        for (int f = 0; f < fieldCount; f++) {
            for (int p = 0; p < points; p++) {
                columns[f][p] = DataSourceDefinition.formatPointColumn(patterns.get(f), p + 1);
                allColumns.add(columns[f][p]);
            }
        }
        return select(def, allColumns, def.dateColumn(), start.toLocalDate(), end.toLocalDate(), keys,
                List.of(def.dateColumn()),
                (rs, key, sink) -> {
                    LocalDate date = rs.getObject(def.dateColumn(), LocalDate.class);
                    if (date == null) {
                        return;
                    }
                    List<DataRow> rows = sink.computeIfAbsent(key, k -> new ArrayList<>());
                    LocalDateTime dayStart = date.atStartOfDay();
                    for (int p = 0; p < points; p++) {
                        double[] values = new double[fieldCount];
                        boolean allMissing = true;
                        for (int f = 0; f < fieldCount; f++) {
                            values[f] = readDouble(rs, columns[f][p]);
                            allMissing &= Double.isNaN(values[f]);
                        }
                        // 宽表中未来时段常为空，整行缺失则不输出
                        if (!allMissing) {
                            rows.add(new DataRow(dayStart.plusMinutes((long) p * minutes), values));
                        }
                    }
                });
    }
}
