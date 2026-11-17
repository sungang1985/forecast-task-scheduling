package com.forecast.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 任务提交结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务提交结果")
public class TaskSubmitResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;
    private String status;
    private String traceId;
    /** 是否命中幂等（已存在相同 requestId 的任务） */
    private boolean duplicated;
    /** 同步等待（waitSeconds>0）且在等待时间内结束时返回任务详情 */
    private TaskDetail detail;
}
