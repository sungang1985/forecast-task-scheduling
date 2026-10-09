package com.forecast.task.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forecast.api.dto.ForecastPointVO;
import com.forecast.common.model.PageResult;
import com.forecast.task.domain.entity.ForecastResult;
import com.forecast.task.engine.dto.EngineDtos.PredictionRow;
import com.forecast.task.mapper.ForecastResultMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 预测结果存储（默认结果落库实现）。写入使用 JDBC 批量插入（MySQL 需开启 rewriteBatchedStatements）。
 */
@Service
public class ForecastResultService implements ResultSink {

    private static final String INSERT_SQL = "INSERT INTO forecast_result (task_id, task_type, job_id, series_key, "
            + "target_field, forecast_time, forecast_value, lower_bound, upper_bound, model_id, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final int BATCH_SIZE = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final ForecastResultMapper mapper;

    public ForecastResultService(JdbcTemplate jdbcTemplate, ForecastResultMapper mapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "forecast_result";
    }

    /** 任务重试时先清理该任务已写入的结果，保证幂等 */
    @Override
    public void prepare(ResultContext context) {
        mapper.delete(Wrappers.<ForecastResult>lambdaQuery().eq(ForecastResult::getTaskId, context.taskId()));
    }

    @Override
    public void write(ResultContext context, List<PredictionRow> rows) {
        // 统一使用 java.time 类型写入，避免 java.sql.Timestamp 在 JVM 与数据库时区不一致时被换算
        LocalDateTime now = LocalDateTime.now();
        for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
            List<PredictionRow> batch = rows.subList(from, Math.min(rows.size(), from + BATCH_SIZE));
            jdbcTemplate.batchUpdate(INSERT_SQL, batch, batch.size(), (ps, row) -> {
                ps.setString(1, context.taskId());
                ps.setString(2, context.taskType());
                if (context.jobId() == null) {
                    ps.setNull(3, Types.BIGINT);
                } else {
                    ps.setLong(3, context.jobId());
                }
                ps.setString(4, row.seriesKey());
                ps.setString(5, row.target());
                ps.setObject(6, row.ts());
                ps.setBigDecimal(7, decimal(row.value()));
                ps.setBigDecimal(8, decimal(row.lower()));
                ps.setBigDecimal(9, decimal(row.upper()));
                ps.setString(10, context.modelId());
                ps.setObject(11, now);
            });
        }
    }

    public PageResult<ForecastPointVO> pageByTask(String taskId, String seriesKey, String target, long page, long size) {
        Page<ForecastResult> result = mapper.selectPage(Page.of(page, size), Wrappers.<ForecastResult>lambdaQuery()
                .eq(ForecastResult::getTaskId, taskId)
                .eq(StringUtils.hasText(seriesKey), ForecastResult::getSeriesKey, seriesKey)
                .eq(StringUtils.hasText(target), ForecastResult::getTargetField, target)
                .orderByAsc(ForecastResult::getSeriesKey, ForecastResult::getTargetField, ForecastResult::getForecastTime));
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(),
                result.getRecords().stream().map(ForecastResultService::toVO).toList());
    }

    /**
     * 查询某预测对象在时间范围内“最新一次”的预测结果。
     */
    public List<ForecastPointVO> latest(String taskType, String seriesKey, String target,
                                        LocalDateTime start, LocalDateTime end) {
        ForecastResult latest = mapper.selectOne(Wrappers.<ForecastResult>lambdaQuery()
                .select(ForecastResult::getTaskId)
                .eq(ForecastResult::getTaskType, taskType)
                .eq(ForecastResult::getSeriesKey, seriesKey)
                .eq(ForecastResult::getTargetField, target)
                .ge(ForecastResult::getForecastTime, start)
                .lt(ForecastResult::getForecastTime, end)
                .orderByDesc(ForecastResult::getId)
                .last("LIMIT 1"));
        if (latest == null) {
            return List.of();
        }
        return mapper.selectList(Wrappers.<ForecastResult>lambdaQuery()
                        .eq(ForecastResult::getTaskId, latest.getTaskId())
                        .eq(ForecastResult::getSeriesKey, seriesKey)
                        .eq(ForecastResult::getTargetField, target)
                        .ge(ForecastResult::getForecastTime, start)
                        .lt(ForecastResult::getForecastTime, end)
                        .orderByAsc(ForecastResult::getForecastTime))
                .stream().map(ForecastResultService::toVO).toList();
    }

    public long countByTask(String taskId) {
        return mapper.selectCount(Wrappers.<ForecastResult>lambdaQuery().eq(ForecastResult::getTaskId, taskId));
    }

    private static BigDecimal decimal(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static ForecastPointVO toVO(ForecastResult r) {
        ForecastPointVO vo = new ForecastPointVO();
        vo.setTaskId(r.getTaskId());
        vo.setTaskType(r.getTaskType());
        vo.setSeriesKey(r.getSeriesKey());
        vo.setTargetField(r.getTargetField());
        vo.setForecastTime(r.getForecastTime());
        vo.setForecastValue(r.getForecastValue());
        vo.setLowerBound(r.getLowerBound());
        vo.setUpperBound(r.getUpperBound());
        vo.setModelId(r.getModelId());
        vo.setCreatedAt(r.getCreatedAt());
        return vo;
    }
}
