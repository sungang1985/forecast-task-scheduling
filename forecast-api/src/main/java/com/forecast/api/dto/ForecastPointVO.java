package com.forecast.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预测结果点。
 */
@Data
@Schema(description = "预测结果点")
public class ForecastPointVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;
    private String taskType;
    private String seriesKey;
    private String targetField;
    private LocalDateTime forecastTime;
    private BigDecimal forecastValue;
    private BigDecimal lowerBound;
    private BigDecimal upperBound;
    private String modelId;
    private LocalDateTime createdAt;
}
