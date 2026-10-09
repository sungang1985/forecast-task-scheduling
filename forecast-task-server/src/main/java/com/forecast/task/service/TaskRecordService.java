package com.forecast.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forecast.common.enums.StepStatus;
import com.forecast.common.enums.TaskStatus;
import com.forecast.common.util.JsonUtils;
import com.forecast.task.domain.entity.TaskInstance;
import com.forecast.task.domain.entity.TaskStep;
import com.forecast.task.mapper.TaskInstanceMapper;
import com.forecast.task.mapper.TaskStepMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务与步骤执行记录的持久化（状态流转均使用带前置状态的条件更新，保证并发安全）。
 */
@Slf4j
@Service
public class TaskRecordService {

    private static final List<String> ACTIVE = TaskStatus.activeStatuses().stream().map(Enum::name).toList();
    private static final long PROGRESS_THROTTLE_MS = 1000;

    private final TaskInstanceMapper instanceMapper;
    private final TaskStepMapper stepMapper;
    /** 进度写库节流：taskId -> 上次写入时间 */
    private final Map<String, Long> progressWrites = new ConcurrentHashMap<>();

    public TaskRecordService(TaskInstanceMapper instanceMapper, TaskStepMapper stepMapper) {
        this.instanceMapper = instanceMapper;
        this.stepMapper = stepMapper;
    }

    public void create(TaskInstance instance) {
        instanceMapper.insert(instance);
    }

    public TaskInstance get(String taskId) {
        return instanceMapper.selectOne(Wrappers.<TaskInstance>lambdaQuery().eq(TaskInstance::getTaskId, taskId));
    }

    public TaskInstance findByRequestId(String requestId) {
        return instanceMapper.selectOne(Wrappers.<TaskInstance>lambdaQuery().eq(TaskInstance::getRequestId, requestId));
    }

    public List<TaskStep> steps(String taskId) {
        return stepMapper.selectList(Wrappers.<TaskStep>lambdaQuery()
                .eq(TaskStep::getTaskId, taskId)
                .orderByAsc(TaskStep::getStepOrder, TaskStep::getStartTime));
    }

    /** PENDING -> RUNNING，返回 false 表示任务已被取消或已被其他线程处理 */
    public boolean markRunning(String taskId, String node, String traceId) {
        LocalDateTime now = LocalDateTime.now();
        return instanceMapper.update(null, Wrappers.<TaskInstance>lambdaUpdate()
                .set(TaskInstance::getStatus, TaskStatus.RUNNING.name())
                .set(TaskInstance::getStartTime, now)
                .set(TaskInstance::getHeartbeatTime, now)
                .set(TaskInstance::getExecutorNode, node)
                .set(StringUtils.hasText(traceId), TaskInstance::getTraceId, traceId)
                .set(TaskInstance::getUpdatedAt, now)
                .eq(TaskInstance::getTaskId, taskId)
                .eq(TaskInstance::getStatus, TaskStatus.PENDING.name())) > 0;
    }

    public void updateEngineEndpoint(String taskId, String endpoint) {
        instanceMapper.update(null, Wrappers.<TaskInstance>lambdaUpdate()
                .set(TaskInstance::getEngineEndpoint, endpoint)
                .eq(TaskInstance::getTaskId, taskId));
    }

    public void updateProgress(String taskId, int progress, String currentStep, boolean force) {
        long now = System.currentTimeMillis();
        Long last = progressWrites.get(taskId);
        if (!force && last != null && now - last < PROGRESS_THROTTLE_MS) {
            return;
        }
        progressWrites.put(taskId, now);
        instanceMapper.update(null, Wrappers.<TaskInstance>lambdaUpdate()
                .set(TaskInstance::getProgress, progress)
                .set(currentStep != null, TaskInstance::getCurrentStep, currentStep)
                .set(TaskInstance::getUpdatedAt, LocalDateTime.now())
                .eq(TaskInstance::getTaskId, taskId)
                .eq(TaskInstance::getStatus, TaskStatus.RUNNING.name()));
    }

    /**
     * 结束任务（仅当仍处于活动状态时生效，避免覆盖已取消/已被回收的状态）。
     */
    public boolean markFinished(String taskId, TaskStatus status, Map<String, Object> summary, String errorCode,
                                String errorMessage, String modelId) {
        progressWrites.remove(taskId);
        LocalDateTime now = LocalDateTime.now();
        TaskInstance current = get(taskId);
        Long duration = current != null && current.getStartTime() != null
                ? Duration.between(current.getStartTime(), now).toMillis() : null;
        return instanceMapper.update(null, Wrappers.<TaskInstance>lambdaUpdate()
                .set(TaskInstance::getStatus, status.name())
                .set(status == TaskStatus.SUCCESS, TaskInstance::getProgress, 100)
                .set(TaskInstance::getEndTime, now)
                .set(TaskInstance::getDurationMs, duration)
                .set(summary != null, TaskInstance::getResultJson, JsonUtils.toJson(summary))
                .set(TaskInstance::getErrorCode, errorCode)
                .set(TaskInstance::getErrorMessage, truncate(errorMessage, 2000))
                .set(modelId != null, TaskInstance::getModelId, modelId)
                .set(TaskInstance::getUpdatedAt, now)
                .eq(TaskInstance::getTaskId, taskId)
                .in(TaskInstance::getStatus, ACTIVE)) > 0;
    }

    /** 请求取消：PENDING 任务直接置为 CANCELLED；RUNNING 任务置取消标记，由执行节点感知后中止 */
    public boolean requestCancel(String taskId) {
        LocalDateTime now = LocalDateTime.now();
        int pending = instanceMapper.update(null, Wrappers.<TaskInstance>lambdaUpdate()
                .set(TaskInstance::getStatus, TaskStatus.CANCELLED.name())
                .set(TaskInstance::getCancelRequested, true)
                .set(TaskInstance::getEndTime, now)
                .set(TaskInstance::getErrorMessage, "任务在排队中被取消")
                .set(TaskInstance::getUpdatedAt, now)
                .eq(TaskInstance::getTaskId, taskId)
                .eq(TaskInstance::getStatus, TaskStatus.PENDING.name()));
        if (pending > 0) {
            return true;
        }
        return instanceMapper.update(null, Wrappers.<TaskInstance>lambdaUpdate()
                .set(TaskInstance::getCancelRequested, true)
                .set(TaskInstance::getUpdatedAt, now)
                .eq(TaskInstance::getTaskId, taskId)
                .eq(TaskInstance::getStatus, TaskStatus.RUNNING.name())) > 0;
    }

    public List<String> findCancelRequested(Collection<String> taskIds) {
        if (taskIds.isEmpty()) {
            return List.of();
        }
        return instanceMapper.selectList(Wrappers.<TaskInstance>lambdaQuery()
                        .select(TaskInstance::getTaskId)
                        .in(TaskInstance::getTaskId, taskIds)
                        .eq(TaskInstance::getCancelRequested, true))
                .stream().map(TaskInstance::getTaskId).toList();
    }

    public void heartbeat(Collection<String> taskIds) {
        if (taskIds.isEmpty()) {
            return;
        }
        instanceMapper.update(null, Wrappers.<TaskInstance>lambdaUpdate()
                .set(TaskInstance::getHeartbeatTime, LocalDateTime.now())
                .in(TaskInstance::getTaskId, taskIds)
                .in(TaskInstance::getStatus, ACTIVE));
    }

    /**
     * 回收僵尸任务：心跳超时的活动任务（执行节点宕机/被强杀）标记为失败。
     */
    public List<TaskInstance> failStaleTasks(Duration staleTimeout) {
        LocalDateTime threshold = LocalDateTime.now().minus(staleTimeout);
        List<TaskInstance> stale = instanceMapper.selectList(Wrappers.<TaskInstance>lambdaQuery()
                .in(TaskInstance::getStatus, ACTIVE)
                .lt(TaskInstance::getHeartbeatTime, threshold)
                .last("LIMIT 200"));
        List<TaskInstance> failed = new java.util.ArrayList<>();
        for (TaskInstance t : stale) {
            LocalDateTime now = LocalDateTime.now();
            int updated = instanceMapper.update(null, Wrappers.<TaskInstance>lambdaUpdate()
                    .set(TaskInstance::getStatus, TaskStatus.FAILED.name())
                    .set(TaskInstance::getErrorCode, "EXECUTOR_LOST")
                    .set(TaskInstance::getErrorMessage, "执行节点心跳超时（节点 " + t.getExecutorNode() + " 可能已宕机）")
                    .set(TaskInstance::getEndTime, now)
                    .set(TaskInstance::getUpdatedAt, now)
                    .eq(TaskInstance::getTaskId, t.getTaskId())
                    .in(TaskInstance::getStatus, ACTIVE)
                    .lt(TaskInstance::getHeartbeatTime, threshold));
            if (updated > 0) {
                failed.add(t);
            }
        }
        return failed;
    }

    public Long stepStarted(String taskId, int order, String name, int attempt) {
        TaskStep step = new TaskStep();
        step.setTaskId(taskId);
        step.setStepOrder(order);
        step.setStepName(name);
        step.setStatus(StepStatus.RUNNING.name());
        step.setAttempt(attempt);
        step.setStartTime(LocalDateTime.now());
        stepMapper.insert(step);
        return step.getId();
    }

    public void stepFinished(Long stepId, StepStatus status, String message, Map<String, Object> output,
                             String engineTaskId, long durationMs) {
        TaskStep update = new TaskStep();
        update.setId(stepId);
        update.setStatus(status.name());
        update.setMessage(truncate(message, 2000));
        update.setOutputJson(output == null || output.isEmpty() ? null : JsonUtils.toJson(output));
        update.setEngineTaskId(engineTaskId);
        update.setEndTime(LocalDateTime.now());
        update.setDurationMs(durationMs);
        stepMapper.updateById(update);
    }

    public void stepSkipped(String taskId, int order, String name, String reason) {
        TaskStep step = new TaskStep();
        step.setTaskId(taskId);
        step.setStepOrder(order);
        step.setStepName(name);
        step.setStatus(StepStatus.SKIPPED.name());
        step.setAttempt(0);
        step.setMessage(reason);
        step.setStartTime(LocalDateTime.now());
        step.setEndTime(step.getStartTime());
        step.setDurationMs(0L);
        stepMapper.insert(step);
    }

    public Page<TaskInstance> page(LambdaQueryWrapper<TaskInstance> query, long page, long size) {
        return instanceMapper.selectPage(Page.of(page, size), query);
    }

    public List<TaskInstance> list(LambdaQueryWrapper<TaskInstance> query) {
        return instanceMapper.selectList(query);
    }

    public long count(LambdaQueryWrapper<TaskInstance> query) {
        return instanceMapper.selectCount(query);
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
