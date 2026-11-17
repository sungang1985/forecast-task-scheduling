package com.forecast.task.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预测任务实例。
 */
@Data
@TableName("forecast_task_instance")
public class TaskInstance {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String taskId;
    private String taskType;
    private String mode;
    private String status;
    private Integer progress;
    private String currentStep;
    private String triggerType;
    private Long jobId;
    private LocalDateTime jobFireTime;
    private String requestId;
    private String parentTaskId;
    private Integer retryCount;
    private String traceId;
    private String executorNode;
    private String engineEndpoint;
    private String modelId;
    private String requestJson;
    private String resultJson;
    private String errorCode;
    private String errorMessage;
    private Boolean cancelRequested;
    private String callbackUrl;
    private String callbackStatus;
    private Integer timeoutSeconds;
    private LocalDateTime submitTime;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
    private LocalDateTime heartbeatTime;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
