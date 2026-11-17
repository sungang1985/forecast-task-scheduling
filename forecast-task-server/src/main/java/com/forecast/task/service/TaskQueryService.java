package com.forecast.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forecast.api.dto.TaskDetail;
import com.forecast.api.dto.TaskStepDetail;
import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import com.forecast.common.model.PageResult;
import com.forecast.common.util.JsonUtils;
import com.forecast.task.domain.entity.TaskInstance;
import com.forecast.task.domain.entity.TaskStep;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 任务查询（详情含步骤轨迹）。
 */
@Service
public class TaskQueryService {

    private final TaskRecordService recordService;

    public TaskQueryService(TaskRecordService recordService) {
        this.recordService = recordService;
    }

    public TaskDetail detail(String taskId) {
        TaskInstance instance = recordService.get(taskId);
        if (instance == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND, "任务不存在: " + taskId);
        }
        TaskDetail detail = toDetail(instance, true);
        recordService.steps(taskId).forEach(step -> detail.getSteps().add(toStep(step)));
        return detail;
    }

    public PageResult<TaskDetail> page(String taskType, String mode, String status, String triggerType, Long jobId,
                                       LocalDateTime from, LocalDateTime to, long page, long size) {
        LambdaQueryWrapper<TaskInstance> query = Wrappers.<TaskInstance>lambdaQuery()
                .eq(StringUtils.hasText(taskType), TaskInstance::getTaskType, taskType == null ? null : taskType.toUpperCase())
                .eq(StringUtils.hasText(mode), TaskInstance::getMode, mode == null ? null : mode.toUpperCase())
                .eq(StringUtils.hasText(status), TaskInstance::getStatus, status == null ? null : status.toUpperCase())
                .eq(StringUtils.hasText(triggerType), TaskInstance::getTriggerType, triggerType == null ? null : triggerType.toUpperCase())
                .eq(jobId != null, TaskInstance::getJobId, jobId)
                .ge(from != null, TaskInstance::getSubmitTime, from)
                .lt(to != null, TaskInstance::getSubmitTime, to)
                .orderByDesc(TaskInstance::getSubmitTime);
        Page<TaskInstance> result = recordService.page(query, page, size);
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(),
                result.getRecords().stream().map(t -> toDetail(t, false)).toList());
    }

    public static TaskDetail toDetail(TaskInstance t, boolean withRequest) {
        TaskDetail d = new TaskDetail();
        d.setTaskId(t.getTaskId());
        d.setTaskType(t.getTaskType());
        d.setMode(t.getMode());
        d.setStatus(t.getStatus());
        d.setProgress(t.getProgress());
        d.setCurrentStep(t.getCurrentStep());
        d.setTriggerType(t.getTriggerType());
        d.setJobId(t.getJobId() == null ? null : String.valueOf(t.getJobId()));
        d.setRequestId(t.getRequestId());
        d.setParentTaskId(t.getParentTaskId());
        d.setRetryCount(t.getRetryCount());
        d.setTraceId(t.getTraceId());
        d.setExecutorNode(t.getExecutorNode());
        d.setModelId(t.getModelId());
        if (withRequest) {
            d.setRequest(JsonUtils.toMap(t.getRequestJson()));
        }
        d.setResultSummary(JsonUtils.toMap(t.getResultJson()));
        d.setErrorCode(t.getErrorCode());
        d.setErrorMessage(t.getErrorMessage());
        d.setSubmitTime(t.getSubmitTime());
        d.setStartTime(t.getStartTime());
        d.setEndTime(t.getEndTime());
        d.setDurationMs(t.getDurationMs());
        return d;
    }

    private static TaskStepDetail toStep(TaskStep s) {
        TaskStepDetail d = new TaskStepDetail();
        d.setStepOrder(s.getStepOrder());
        d.setStepName(s.getStepName());
        d.setStatus(s.getStatus());
        d.setAttempt(s.getAttempt());
        d.setEngineTaskId(s.getEngineTaskId());
        d.setMessage(s.getMessage());
        d.setOutput(JsonUtils.toMap(s.getOutputJson()));
        d.setStartTime(s.getStartTime());
        d.setEndTime(s.getEndTime());
        d.setDurationMs(s.getDurationMs());
        return d;
    }
}
