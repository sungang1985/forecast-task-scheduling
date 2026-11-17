package com.forecast.task.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预测结果（写入走 JdbcTemplate 批量插入，查询走 MyBatis-Plus）。
 */
@Data
@TableName("forecast_result")
public class ForecastResult {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskId;
    private String taskType;
    private Long jobId;
    private String seriesKey;
    private String targetField;
    private LocalDateTime forecastTime;
    private BigDecimal forecastValue;
    private BigDecimal lowerBound;
    private BigDecimal upperBound;
    private String modelId;
    private LocalDateTime createdAt;
}
