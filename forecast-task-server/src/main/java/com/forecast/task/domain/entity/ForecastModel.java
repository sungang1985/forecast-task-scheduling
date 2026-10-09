package com.forecast.task.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型注册信息。
 */
@Data
@TableName("forecast_model")
public class ForecastModel {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    /** 训练覆盖全部预测对象 */
    public static final String ALL_SERIES = "*";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String modelId;
    private String taskType;
    private String algorithm;
    private String seriesKeys;
    private String targetFields;
    private LocalDateTime trainStart;
    private LocalDateTime trainEnd;
    private Long trainRows;
    private String metricsJson;
    private String paramsJson;
    private String status;
    private String sourceTaskId;
    private String engineEndpoint;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
