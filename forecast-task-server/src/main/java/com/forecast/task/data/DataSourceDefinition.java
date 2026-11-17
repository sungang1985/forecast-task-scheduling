package com.forecast.task.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import com.forecast.common.util.JsonUtils;
import com.forecast.task.domain.entity.DataSourceConfig;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 校验并解析后的数据源定义（不可变）。
 *
 * @param fieldMapping 统一字段名 -> 列名；WIDE_POINTS 布局下为列名格式（String.format，参数为 1..pointsPerDay 的点序号）
 * @param fixedFilters 固定过滤条件：列名 -> 值（参数绑定）
 */
public record DataSourceDefinition(
        String sourceCode,
        String sourceName,
        String tableName,
        DatasetLayout layout,
        String keyColumn,
        String timeColumn,
        String dateColumn,
        String periodColumn,
        int pointsPerDay,
        Map<String, String> fieldMapping,
        Map<String, Object> fixedFilters,
        SegmentUnit segmentUnit,
        boolean cacheEnabled) {

    /** 无 key 列时使用的默认预测对象 */
    public static final String SINGLE_SERIES_KEY = "ALL";

    private static final TypeReference<LinkedHashMap<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    public List<String> fieldNames() {
        return new ArrayList<>(fieldMapping.keySet());
    }

    public boolean hasKeyColumn() {
        return keyColumn != null;
    }

    /** 每个时段的分钟数 */
    public int minutesPerPoint() {
        return 1440 / pointsPerDay;
    }

    /**
     * 从数据库配置解析并做完整校验（标识符、布局必填项、点数合法性）。
     */
    public static DataSourceDefinition from(DataSourceConfig cfg) {
        String code = cfg.getSourceCode();
        if (!StringUtils.hasText(code) || !code.matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw new BizException(ErrorCode.DATA_SOURCE_INVALID, "sourceCode 仅允许字母、数字、_、-: " + code);
        }
        DatasetLayout layout;
        try {
            layout = DatasetLayout.valueOf(cfg.getLayout().trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new BizException(ErrorCode.DATA_SOURCE_INVALID, "不支持的表结构布局: " + cfg.getLayout());
        }
        SegmentUnit unit = StringUtils.hasText(cfg.getSegmentUnit())
                ? SegmentUnit.valueOf(cfg.getSegmentUnit().trim().toUpperCase(Locale.ROOT))
                : SegmentUnit.MONTH;

        Map<String, String> mapping;
        Map<String, Object> filters;
        try {
            mapping = JsonUtils.fromJson(cfg.getFieldMapping(), STRING_MAP);
            filters = JsonUtils.fromJson(cfg.getFixedFilters(), OBJECT_MAP);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.DATA_SOURCE_INVALID, "字段映射/过滤条件不是合法 JSON: " + e.getMessage());
        }
        if (mapping == null || mapping.isEmpty()) {
            throw new BizException(ErrorCode.DATA_SOURCE_INVALID, "fieldMapping 不能为空");
        }
        filters = filters == null ? Collections.emptyMap() : filters;

        String table = SqlIdentifiers.table(cfg.getTableName());
        String keyColumn = StringUtils.hasText(cfg.getKeyColumn()) ? SqlIdentifiers.column(cfg.getKeyColumn(), "keyColumn") : null;
        String timeColumn = null;
        String dateColumn = null;
        String periodColumn = null;
        int points = cfg.getPointsPerDay() == null ? 0 : cfg.getPointsPerDay();

        switch (layout) {
            case LONG_TIME -> timeColumn = SqlIdentifiers.column(cfg.getTimeColumn(), "timeColumn");
            case DATE_PERIOD -> {
                dateColumn = SqlIdentifiers.column(cfg.getDateColumn(), "dateColumn");
                periodColumn = SqlIdentifiers.column(cfg.getPeriodColumn(), "periodColumn");
                validatePoints(points);
            }
            case WIDE_POINTS -> {
                dateColumn = SqlIdentifiers.column(cfg.getDateColumn(), "dateColumn");
                validatePoints(points);
            }
        }

        for (Map.Entry<String, String> e : mapping.entrySet()) {
            if (!SqlIdentifiers.isValidColumn(e.getKey())) {
                throw new BizException(ErrorCode.DATA_SOURCE_INVALID, "统一字段名非法: " + e.getKey());
            }
            if (layout == DatasetLayout.WIDE_POINTS) {
                // 校验格式化后的首尾列名
                SqlIdentifiers.column(formatPointColumn(e.getValue(), 1), "宽表列名格式");
                SqlIdentifiers.column(formatPointColumn(e.getValue(), points), "宽表列名格式");
            } else {
                SqlIdentifiers.column(e.getValue(), "字段列");
            }
        }
        for (String filterColumn : filters.keySet()) {
            SqlIdentifiers.column(filterColumn, "过滤列");
        }

        return new DataSourceDefinition(code, cfg.getSourceName(), table, layout, keyColumn, timeColumn, dateColumn,
                periodColumn, points, Collections.unmodifiableMap(new LinkedHashMap<>(mapping)),
                Collections.unmodifiableMap(new LinkedHashMap<>(filters)), unit,
                !Boolean.FALSE.equals(cfg.getCacheEnabled()));
    }

    public static String formatPointColumn(String pattern, int point) {
        try {
            return String.format(pattern, point);
        } catch (Exception e) {
            throw new BizException(ErrorCode.DATA_SOURCE_INVALID, "宽表列名格式非法: " + pattern);
        }
    }

    private static void validatePoints(int points) {
        if (points <= 0 || 1440 % points != 0) {
            throw new BizException(ErrorCode.DATA_SOURCE_INVALID, "pointsPerDay 必须能整除 1440，当前: " + points);
        }
    }
}
