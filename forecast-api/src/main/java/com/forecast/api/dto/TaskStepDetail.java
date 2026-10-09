package com.forecast.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 流程步骤执行记录。
 */
@Data
@Schema(description = "流程步骤执行记录")
public class TaskStepDetail implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer stepOrder;
    private String stepName;
    private String status;
    private Integer attempt;
    private String engineTaskId;
    private String message;
    private Map<String, Object> output;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
}
