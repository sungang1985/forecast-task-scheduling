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
 * 日期 + 时段序号：时段 p（1..N）对应时间点 date + (p-1) * 每时段分钟数（时段起始时刻标注）。
 */
@Component
public class DatePeriodLayoutHandler extends AbstractJdbcLayoutHandler {

    public DatePeriodLayoutHandler(@Qualifier("dataJdbcTemplate") NamedParameterJdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public DatasetLayout layout() {
        return DatasetLayout.DATE_PERIOD;
    }

    @Override
    public Map<String, List<DataRow>> query(DataSourceDefinition def, Collection<String> keys,
                                            LocalDateTime start, LocalDateTime end) {
        List<String> valueColumns = new ArrayList<>(def.fieldMapping().values());
        int n = valueColumns.size();
        int minutes = def.minutesPerPoint();
        int points = def.pointsPerDay();
        return select(def, valueColumns, def.dateColumn(), start.toLocalDate(), end.toLocalDate(), keys,
                List.of(def.dateColumn(), def.periodColumn()),
                (rs, key, sink) -> {
                    LocalDate date = rs.getObject(def.dateColumn(), LocalDate.class);
                    int period = rs.getInt(def.periodColumn());
                    if (date == null || period < 1 || period > points) {
                        return;
                    }
                    double[] values = new double[n];
                    for (int i = 0; i < n; i++) {
                        values[i] = readDouble(rs, valueColumns.get(i));
                    }
                    LocalDateTime ts = date.atStartOfDay().plusMinutes((long) (period - 1) * minutes);
                    sink.computeIfAbsent(key, k -> new ArrayList<>()).add(new DataRow(ts, values));
                });
    }
}
