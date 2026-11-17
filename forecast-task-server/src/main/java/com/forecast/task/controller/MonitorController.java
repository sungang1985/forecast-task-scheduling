package com.forecast.task.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.forecast.common.enums.TaskStatus;
import com.forecast.common.model.R;
import com.forecast.task.config.NodeIdentity;
import com.forecast.task.data.cache.SegmentCache;
import com.forecast.task.domain.entity.TaskInstance;
import com.forecast.task.monitor.PythonEngineHealthIndicator;
import com.forecast.task.monitor.TaskEventStreamService;
import com.forecast.task.pipeline.PipelineContext;
import com.forecast.task.schedule.ForecastJobScheduler;
import com.forecast.task.service.RunningTaskRegistry;
import com.forecast.task.service.TaskRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行监控总览：任务状态统计、本节点运行情况、Python 引擎健康、缓存命中、调度状态。
 * 细粒度指标请使用 /actuator/prometheus 接入 Prometheus + Grafana。
 */
@Tag(name = "运行监控", description = "任务执行总览")
@RestController
@RequestMapping("/api/v1/monitor")
public class MonitorController {

    private final TaskRecordService recordService;
    private final RunningTaskRegistry runningRegistry;
    private final SegmentCache segmentCache;
    private final PythonEngineHealthIndicator engineHealth;
    private final ForecastJobScheduler scheduler;
    private final TaskEventStreamService streamService;
    private final NodeIdentity nodeIdentity;

    public MonitorController(TaskRecordService recordService, RunningTaskRegistry runningRegistry,
                             SegmentCache segmentCache, PythonEngineHealthIndicator engineHealth,
                             ForecastJobScheduler scheduler, TaskEventStreamService streamService,
                             NodeIdentity nodeIdentity) {
        this.recordService = recordService;
        this.runningRegistry = runningRegistry;
        this.segmentCache = segmentCache;
        this.engineHealth = engineHealth;
        this.scheduler = scheduler;
        this.streamService = streamService;
        this.nodeIdentity = nodeIdentity;
    }

    @Operation(summary = "监控总览", description = "hours：统计最近多少小时的任务")
    @GetMapping("/overview")
    public R<Map<String, Object>> overview(@RequestParam(defaultValue = "24") int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(Math.max(1, Math.min(hours, 24 * 31)));
        Map<String, Object> statusCount = new LinkedHashMap<>();
        for (TaskStatus status : TaskStatus.values()) {
            statusCount.put(status.name(), recordService.count(Wrappers.<TaskInstance>lambdaQuery()
                    .eq(TaskInstance::getStatus, status.name())
                    .ge(TaskInstance::getSubmitTime, since)));
        }
        List<Map<String, Object>> running = runningRegistry.runningContexts().stream().map(this::describe).toList();

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("nodeId", nodeIdentity.getNodeId());
        node.put("runningTasks", runningRegistry.runningCount());
        node.put("queuedTasks", runningRegistry.queuedCount());
        node.put("sseSubscribers", streamService.subscriberCount());
        node.put("scheduledJobs", scheduler.registeredCount());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("since", since);
        result.put("taskStatusCount", statusCount);
        result.put("node", node);
        result.put("running", running);
        result.put("engine", engineHealth.health());
        result.put("cache", segmentCache.stats());
        result.put("recentFailures", recordService.page(Wrappers.<TaskInstance>lambdaQuery()
                        .select(TaskInstance::getTaskId, TaskInstance::getTaskType, TaskInstance::getMode,
                                TaskInstance::getStatus, TaskInstance::getErrorCode, TaskInstance::getErrorMessage,
                                TaskInstance::getEndTime, TaskInstance::getTraceId)
                        .in(TaskInstance::getStatus, TaskStatus.FAILED.name(), TaskStatus.TIMEOUT.name())
                        .ge(TaskInstance::getSubmitTime, since)
                        .orderByDesc(TaskInstance::getEndTime), 1, 10)
                .getRecords());
        return R.ok(result);
    }

    private Map<String, Object> describe(PipelineContext ctx) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("taskId", ctx.getTaskId());
        item.put("taskType", ctx.getTaskType());
        item.put("mode", ctx.getMode());
        item.put("currentStep", ctx.getCurrentStep());
        item.put("engineTaskId", ctx.getCurrentEngineTaskId());
        item.put("engine", ctx.getEngineEndpoint() == null ? null : ctx.getEngineEndpoint().baseUrl());
        item.put("traceId", ctx.getTraceId());
        item.put("deadline", ctx.getDeadline());
        return item;
    }
}
