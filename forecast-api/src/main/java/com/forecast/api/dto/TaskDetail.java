package com.forecast.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 预测任务详情（含步骤执行轨迹）。
 */
@Data
@Schema(description = "预测任务详情")
public class TaskDetail implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;
    private String taskType;
    private String mode;
    private String status;
    @Schema(description = "进度 0-100")
    private Integer progress;
    private String currentStep;
    private String triggerType;
    private String jobId;
    private String requestId;
    private String parentTaskId;
    private Integer retryCount;
    private String traceId;
    private String executorNode;
    private String modelId;
    private Map<String, Object> request;
    private Map<String, Object> resultSummary;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime submitTime;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
    private List<TaskStepDetail> steps = new ArrayList<>();
}
