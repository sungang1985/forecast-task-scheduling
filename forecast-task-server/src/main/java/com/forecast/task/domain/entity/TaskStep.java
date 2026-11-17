package com.forecast.task.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流程步骤执行记录。
 */
@Data
@TableName("forecast_task_step")
public class TaskStep {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String taskId;
    private Integer stepOrder;
    private String stepName;
    private String status;
    private Integer attempt;
    private String engineTaskId;
    private String message;
    private String outputJson;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
