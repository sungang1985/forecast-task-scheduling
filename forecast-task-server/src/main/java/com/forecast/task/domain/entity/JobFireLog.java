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
 * 定时任务触发记录。
 */
@Data
@TableName("forecast_job_fire_log")
public class JobFireLog {

    public static final String TRIGGERED = "TRIGGERED";
    public static final String SKIPPED = "SKIPPED";
    public static final String SUBMIT_FAILED = "SUBMIT_FAILED";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long jobId;
    private LocalDateTime fireTime;
    private Integer attempt;
    private String triggerType;
    private LocalDateTime actualFireTime;
    private String node;
    private String taskId;
    private String status;
    private String message;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
