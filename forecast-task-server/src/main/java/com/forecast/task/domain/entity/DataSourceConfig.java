package com.forecast.task.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预训练数据源配置（对应一张业务表）。
 */
@Data
@TableName("forecast_data_source")
@Schema(description = "预训练数据源配置")
public class DataSourceConfig {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @NotBlank
    @Schema(description = "数据源编码", example = "load_curve_96")
    private String sourceCode;
    @NotBlank
    private String sourceName;
    @NotBlank
    @Schema(description = "表名（可带 schema 前缀）", example = "ods_load_curve_96")
    private String tableName;
    @NotBlank
    @Schema(description = "表结构布局 LONG_TIME / DATE_PERIOD / WIDE_POINTS")
    private String layout;
    private String keyColumn;
    private String timeColumn;
    private String dateColumn;
    private String periodColumn;
    private Integer pointsPerDay;
    @NotBlank
    @Schema(description = "字段映射 JSON", example = "{\"load\":\"p%03d\"}")
    private String fieldMapping;
    @Schema(description = "固定过滤条件 JSON", example = "{\"price_type\":\"DA\"}")
    private String fixedFilters;
    @Schema(description = "缓存分段粒度 DAY / MONTH")
    private String segmentUnit;
    private Boolean cacheEnabled;
    private Boolean enabled;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
