package com.forecast.task.data.layout;

import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import com.forecast.task.data.DataRow;
import com.forecast.task.data.DataSourceDefinition;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC 布局适配器基类：统一拼装 “时间范围 + 预测对象 + 固定过滤” 的参数化 SQL。
 */
public abstract class AbstractJdbcLayoutHandler implements LayoutHandler {

    protected final NamedParameterJdbcTemplate jdbc;

    protected AbstractJdbcLayoutHandler(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @FunctionalInterface
    protected interface RowExtractor {
        void extract(ResultSet rs, String seriesKey, Map<String, List<DataRow>> sink) throws SQLException;
    }

    protected Map<String, List<DataRow>> select(DataSourceDefinition def, List<String> valueColumns, String rangeColumn,
                                                Object start, Object end, Collection<String> keys,
                                                List<String> extraColumns, RowExtractor extractor) {
        List<String> columns = new ArrayList<>();
        if (def.hasKeyColumn()) {
            columns.add(def.keyColumn());
        }
        columns.addAll(extraColumns);
        columns.addAll(valueColumns);

        StringBuilder sql = new StringBuilder(256)
                .append("SELECT ").append(String.join(", ", columns))
                .append(" FROM ").append(def.tableName())
                .append(" WHERE ").append(rangeColumn).append(" >= :rangeStart AND ")
                .append(rangeColumn).append(" < :rangeEnd");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("rangeStart", start)
                .addValue("rangeEnd", end);
        if (keys != null && def.hasKeyColumn()) {
            sql.append(" AND ").append(def.keyColumn()).append(" IN (:keys)");
            params.addValue("keys", keys);
        }
        int i = 0;
        for (Map.Entry<String, Object> filter : def.fixedFilters().entrySet()) {
            String param = "ff" + i++;
            sql.append(" AND ").append(filter.getKey()).append(" = :").append(param);
            params.addValue(param, filter.getValue());
        }

        Map<String, List<DataRow>> result = new HashMap<>();
        try {
            jdbc.query(sql.toString(), params, rs -> {
                String key = def.hasKeyColumn() ? rs.getString(def.keyColumn()) : DataSourceDefinition.SINGLE_SERIES_KEY;
                extractor.extract(rs, key, result);
            });
        } catch (DataAccessException e) {
            throw new BizException(ErrorCode.DATA_READ_FAILED,
                    "读取数据源 " + def.sourceCode() + " 失败: " + e.getMostSpecificCause().getMessage(), e);
        }
        return result;
    }

    protected static double readDouble(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return Double.NaN;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
