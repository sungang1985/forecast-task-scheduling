package com.forecast.task.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.forecast.api.dto.ForecastTaskRequest;
import com.forecast.api.dto.TaskDetail;
import com.forecast.api.dto.TaskSubmitResult;
import com.forecast.common.constant.TraceHeaders;
import com.forecast.common.enums.ForecastMode;
import com.forecast.common.enums.TaskStatus;
import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import com.forecast.common.util.JsonUtils;
import com.forecast.task.config.ExecutorConfig;
import com.forecast.task.config.ForecastProperties;
import com.forecast.task.config.ForecastProperties.TaskTypeProperties;
import com.forecast.task.config.NodeIdentity;
import com.forecast.task.domain.entity.ForecastModel;
import com.forecast.task.domain.entity.TaskInstance;
import com.forecast.task.engine.EngineEndpointResolver;
import com.forecast.task.monitor.TaskEvent;
import com.forecast.task.pipeline.ContextKeys;
import com.forecast.task.pipeline.PipelineContext;
import com.forecast.task.pipeline.PipelineExecutor;
import com.forecast.task.pipeline.PipelineRegistry;
import com.forecast.task.pipeline.TaskCancelledException;
import com.forecast.task.pipeline.TaskTimeoutException;
import com.forecast.task.pipeline.TaskTypeRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 预测任务服务：提交（幂等、校验、限流）→ 异步执行（流程编排）→ 状态流转与事件发布。
 * 接口调用与定时调度共用本服务，保证两种入口的处理过程完全一致。
 */
@Slf4j
@Service
public class ForecastTaskService {

    private final TaskTypeRegistry taskTypeRegistry;
    private final TaskRequestResolver requestResolver;
    private final TaskRecordService recordService;
    private final PipelineRegistry pipelineRegistry;
    private final PipelineExecutor pipelineExecutor;
    private final RunningTaskRegistry runningRegistry;
    private final ModelService modelService;
    private final EngineEndpointResolver endpointResolver;
    private final ThreadPoolTaskExecutor taskExecutor;
    private final ApplicationEventPublisher eventPublisher;
    private final ObservationRegistry observationRegistry;
    private final ObjectProvider<Tracer> tracerProvider;
    private final ForecastProperties properties;
    private final NodeIdentity nodeIdentity;
    private final TaskQueryService queryService;

    public ForecastTaskService(TaskTypeRegistry taskTypeRegistry, TaskRequestResolver requestResolver,
                               TaskRecordService recordService, PipelineRegistry pipelineRegistry,
                               PipelineExecutor pipelineExecutor, RunningTaskRegistry runningRegistry,
                               ModelService modelService, EngineEndpointResolver endpointResolver,
                               @Qualifier(ExecutorConfig.TASK_EXECUTOR) ThreadPoolTaskExecutor taskExecutor,
                               ApplicationEventPublisher eventPublisher, ObservationRegistry observationRegistry,
                               ObjectProvider<Tracer> tracerProvider, ForecastProperties properties,
                               NodeIdentity nodeIdentity, TaskQueryService queryService) {
        this.taskTypeRegistry = taskTypeRegistry;
        this.requestResolver = requestResolver;
        this.recordService = recordService;
        this.pipelineRegistry = pipelineRegistry;
        this.pipelineExecutor = pipelineExecutor;
        this.runningRegistry = runningRegistry;
        this.modelService = modelService;
        this.endpointResolver = endpointResolver;
        this.taskExecutor = taskExecutor;
        this.eventPublisher = eventPublisher;
        this.observationRegistry = observationRegistry;
        this.tracerProvider = tracerProvider;
        this.properties = properties;
        this.nodeIdentity = nodeIdentity;
        this.queryService = queryService;
    }

    /**
     * 提交预测任务（异步执行）。
     */
    public TaskSubmitResult submit(String taskType, ForecastMode mode, ForecastTaskRequest request, SubmitOptions options) {
        String type = TaskTypeRegistry.normalize(taskType);
        TaskTypeProperties def = taskTypeRegistry.require(type);
        // 提前校验流程存在，配置错误时同步报错
        pipelineRegistry.resolve(def, mode);
        ForecastTaskRequest resolved = requestResolver.resolve(def, mode, request);
        validateCallback(resolved.getCallbackUrl());

        if (StringUtils.hasText(resolved.getRequestId())) {
            TaskInstance existing = recordService.findByRequestId(resolved.getRequestId());
            if (existing != null) {
                return duplicated(existing);
            }
        }
        if (mode == ForecastMode.PREDICT && !StringUtils.hasText(resolved.getModelId())) {
            // 预测模式提交时即确定模型，无可用模型立即返回错误，避免空跑
            ForecastModel model = modelService.findUsable(type, resolved.getSeriesKeys(), resolved.getTargetFields());
            resolved.setModelId(model.getModelId());
        }

        LocalDateTime now = LocalDateTime.now();
        TaskInstance instance = new TaskInstance();
        instance.setTaskId("FT" + IdWorker.getIdStr());
        instance.setTaskType(type);
        instance.setMode(mode.name());
        instance.setStatus(TaskStatus.PENDING.name());
        instance.setProgress(0);
        instance.setTriggerType(options.triggerType().name());
        instance.setJobId(options.jobId());
        instance.setJobFireTime(options.jobFireTime());
        instance.setRequestId(StringUtils.hasText(resolved.getRequestId()) ? resolved.getRequestId() : null);
        instance.setParentTaskId(options.parentTaskId());
        instance.setRetryCount(options.retryCount());
        instance.setTraceId(MDC.get(TraceHeaders.MDC_TRACE_ID));
        instance.setExecutorNode(nodeIdentity.getNodeId());
        instance.setModelId(resolved.getModelId());
        instance.setRequestJson(JsonUtils.toJson(resolved));
        instance.setCancelRequested(false);
        instance.setCallbackUrl(resolved.getCallbackUrl());
        instance.setTimeoutSeconds(resolved.getTimeoutSeconds());
        instance.setSubmitTime(now);
        instance.setHeartbeatTime(now);
        try {
            recordService.create(instance);
        } catch (DuplicateKeyException e) {
            // 并发提交同一 requestId
            TaskInstance existing = recordService.findByRequestId(resolved.getRequestId());
            if (existing != null) {
                return duplicated(existing);
            }
            throw e;
        }

        String taskId = instance.getTaskId();
        runningRegistry.queued(taskId);
        Observation parent = observationRegistry.getCurrentObservation();
        try {
            taskExecutor.execute(() -> run(taskId, parent));
        } catch (TaskRejectedException e) {
            runningRegistry.finished(taskId);
            recordService.markFinished(taskId, TaskStatus.FAILED, null, ErrorCode.TOO_MANY_REQUESTS.name(),
                    "任务队列已满，拒绝执行", null);
            publish(instance, TaskEvent.Type.FINISHED, TaskStatus.FAILED.name(), "任务队列已满");
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "预测任务队列已满，请稍后重试");
        }
        publish(instance, TaskEvent.Type.SUBMITTED, TaskStatus.PENDING.name(), null);
        log.info("预测任务已提交 taskId={} type={} mode={} trigger={} jobId={}", taskId, type, mode,
                options.triggerType(), options.jobId());
        return TaskSubmitResult.builder()
                .taskId(taskId)
                .status(TaskStatus.PENDING.name())
                .traceId(instance.getTraceId())
                .build();
    }

    /**
     * 提交并在 waitSeconds 内同步等待结果（适合小规模预测的同步调用场景）。
     */
    public TaskSubmitResult submitAndWait(String taskType, ForecastMode mode, ForecastTaskRequest request,
                                          int waitSeconds) {
        TaskSubmitResult result = submit(taskType, mode, request, SubmitOptions.api());
        int wait = Math.min(Math.max(0, waitSeconds), properties.getTask().getMaxWaitSeconds());
        if (wait > 0) {
            runningRegistry.await(result.getTaskId(), wait);
        }
        if (wait > 0 || result.isDuplicated()) {
            TaskDetail detail = queryService.detail(result.getTaskId());
            result.setStatus(detail.getStatus());
            if (TaskStatus.valueOf(detail.getStatus()).isTerminal()) {
                result.setDetail(detail);
            }
        }
        return result;
    }

    /**
     * 取消任务：排队中立即取消；运行中设置取消标记，执行节点在步骤边界/轮询时感知并中止（同时通知 Python 引擎取消）。
     */
    public boolean cancel(String taskId) {
        TaskInstance instance = recordService.get(taskId);
        if (instance == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND, "任务不存在: " + taskId);
        }
        if (TaskStatus.valueOf(instance.getStatus()).isTerminal()) {
            throw new BizException(ErrorCode.TASK_STATE_INVALID, "任务已结束，状态: " + instance.getStatus());
        }
        boolean accepted = recordService.requestCancel(taskId);
        PipelineContext ctx = runningRegistry.get(taskId);
        if (ctx != null) {
            ctx.requestCancel();
        }
        if (accepted && TaskStatus.PENDING.name().equals(instance.getStatus())) {
            publish(instance, TaskEvent.Type.FINISHED, TaskStatus.CANCELLED.name(), "排队中被取消");
        }
        return accepted;
    }

    /**
     * 基于原任务参数重新提交（新任务，记录来源任务）。
     */
    public TaskSubmitResult retry(String taskId) {
        TaskInstance instance = recordService.get(taskId);
        if (instance == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND, "任务不存在: " + taskId);
        }
        if (!TaskStatus.valueOf(instance.getStatus()).isTerminal()) {
            throw new BizException(ErrorCode.TASK_STATE_INVALID, "任务尚未结束，不能重试");
        }
        ForecastTaskRequest request = JsonUtils.fromJson(instance.getRequestJson(), ForecastTaskRequest.class);
        request.setRequestId(null);
        int retryCount = instance.getRetryCount() == null ? 1 : instance.getRetryCount() + 1;
        return submit(instance.getTaskType(), ForecastMode.parse(instance.getMode()), request,
                SubmitOptions.retryOf(taskId, retryCount, instance.getJobId(), instance.getJobFireTime()));
    }

    /**
     * 执行任务（在 forecastTaskExecutor 线程中）。
     */
    void run(String taskId, Observation parent) {
        MDC.put(TraceHeaders.MDC_TASK_ID, taskId);
        TaskInstance instance = recordService.get(taskId);
        if (instance == null) {
            runningRegistry.finished(taskId);
            MDC.remove(TraceHeaders.MDC_TASK_ID);
            return;
        }
        Observation observation = Observation.createNotStarted("forecast.task", observationRegistry)
                .contextualName("forecast-task " + instance.getTaskType() + " " + instance.getMode())
                .parentObservation(parent)
                .lowCardinalityKeyValue("task.type", instance.getTaskType())
                .lowCardinalityKeyValue("mode", instance.getMode())
                .lowCardinalityKeyValue("trigger", instance.getTriggerType())
                .highCardinalityKeyValue("task.id", taskId)
                .start();
        String finalStatus = TaskStatus.FAILED.name();
        try (Observation.Scope ignored = observation.openScope()) {
            String traceId = currentTraceId(instance.getTraceId());
            if (traceId != null) {
                MDC.put(TraceHeaders.MDC_TRACE_ID, traceId);
            }
            if (!recordService.markRunning(taskId, nodeIdentity.getNodeId(), traceId)) {
                log.info("任务 {} 已被取消或已被处理，跳过执行", taskId);
                finalStatus = recordService.get(taskId).getStatus();
                return;
            }
            finalStatus = execute(instance, observation);
        } finally {
            observation.lowCardinalityKeyValue("status", finalStatus);
            observation.stop();
            runningRegistry.finished(taskId);
            MDC.remove(TraceHeaders.MDC_TASK_ID);
            MDC.remove(TraceHeaders.MDC_TRACE_ID);
        }
    }

    private String execute(TaskInstance instance, Observation observation) {
        String taskId = instance.getTaskId();
        ForecastMode mode = ForecastMode.parse(instance.getMode());
        ForecastTaskRequest request = JsonUtils.fromJson(instance.getRequestJson(), ForecastTaskRequest.class);
        PipelineContext ctx = null;
        TaskStatus status;
        String errorCode = null;
        String errorMessage = null;
        try {
            TaskTypeProperties def = taskTypeRegistry.require(instance.getTaskType());
            Instant deadline = instance.getSubmitTime().atZone(java.time.ZoneId.systemDefault()).toInstant()
                    .plusSeconds(request.getTimeoutSeconds() == null
                            ? properties.getTask().getDefaultTimeout().toSeconds() : request.getTimeoutSeconds());
            ctx = new PipelineContext(taskId, instance.getTaskType(), mode, def, request, instance.getJobId(), deadline);
            ctx.setTraceId(MDC.get(TraceHeaders.MDC_TRACE_ID));
            ctx.setProgressListener((c, progress, message) -> {
                recordService.updateProgress(c.getTaskId(), progress, c.getCurrentStep(), false);
                eventPublisher.publishEvent(TaskEvent.of(c.getTaskId(), c.getTaskType(), c.getMode().name(),
                        TaskEvent.Type.STEP_PROGRESS, c.getCurrentStep(), progress, TaskStatus.RUNNING.name(),
                        message, c.getJobId()));
            });
            if (StringUtils.hasText(request.getProcessedDatasetId())) {
                ctx.put(ContextKeys.PROCESSED_DATASET_ID, request.getProcessedDatasetId());
            }
            if (mode == ForecastMode.PREDICT && StringUtils.hasText(request.getModelId())) {
                ctx.put(ContextKeys.MODEL_ID, request.getModelId());
                pinModelEngine(ctx, request.getModelId());
            }
            runningRegistry.started(ctx);
            publish(instance, TaskEvent.Type.STARTED, TaskStatus.RUNNING.name(), null);
            log.info("预测任务开始执行 taskId={} type={} mode={}", taskId, instance.getTaskType(), mode);

            pipelineExecutor.execute(ctx, pipelineRegistry.resolve(def, mode));
            status = TaskStatus.SUCCESS;
        } catch (TaskCancelledException e) {
            status = TaskStatus.CANCELLED;
            errorCode = e.getErrorCode().name();
            errorMessage = e.getMessage();
        } catch (TaskTimeoutException e) {
            status = TaskStatus.TIMEOUT;
            errorCode = e.getErrorCode().name();
            errorMessage = e.getMessage();
            observation.error(e);
        } catch (BizException e) {
            status = TaskStatus.FAILED;
            errorCode = e.getErrorCode().name();
            errorMessage = e.getMessage();
            observation.error(e);
        } catch (Throwable e) {
            status = TaskStatus.FAILED;
            errorCode = ErrorCode.TASK_EXECUTE_FAILED.name();
            errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
            observation.error(e);
            log.error("预测任务执行异常 taskId={}", taskId, e);
        }

        String modelId = ctx == null ? null : ctx.get(ContextKeys.MODEL_ID);
        boolean updated = recordService.markFinished(taskId, status,
                ctx == null ? null : new java.util.LinkedHashMap<>(ctx.getSummary()), errorCode, errorMessage, modelId);
        if (!updated) {
            // 已被其他流程置为终态（如被回收），以数据库为准
            TaskInstance latest = recordService.get(taskId);
            status = TaskStatus.valueOf(latest.getStatus());
        }
        publish(instance, TaskEvent.Type.FINISHED, status.name(), errorMessage);
        if (status == TaskStatus.SUCCESS) {
            log.info("预测任务执行成功 taskId={} summary={}", taskId, ctx.getSummary());
        } else {
            log.warn("预测任务结束 taskId={} status={} error={}", taskId, status, errorMessage);
        }
        return status.name();
    }

    /**
     * 预测模式：固定到模型所在的引擎实例（多实例且未共享存储时，模型只存在于训练它的实例上）。
     */
    private void pinModelEngine(PipelineContext ctx, String modelId) {
        if (StringUtils.hasText(properties.getEngine().getBaseUrl())) {
            return;
        }
        ForecastModel model = modelService.find(modelId);
        if (model != null && StringUtils.hasText(model.getEngineEndpoint())) {
            ctx.pinEngineEndpoint(endpointResolver.of(model.getEngineEndpoint()));
        }
    }

    private String currentTraceId(String fallback) {
        Tracer tracer = tracerProvider.getIfAvailable();
        if (tracer != null) {
            Span span = tracer.currentSpan();
            if (span != null) {
                return span.context().traceId();
            }
        }
        return fallback;
    }

    private void validateCallback(String callbackUrl) {
        if (!StringUtils.hasText(callbackUrl)) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(callbackUrl);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "callbackUrl 格式非法");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "callbackUrl 仅支持 http/https");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = properties.getCallback().getAllowedHosts().stream()
                .anyMatch(h -> h.equalsIgnoreCase(host));
        if (!allowed) {
            throw new BizException(ErrorCode.FORBIDDEN, "callbackUrl 主机不在白名单内: " + host);
        }
    }

    private TaskSubmitResult duplicated(TaskInstance existing) {
        return TaskSubmitResult.builder()
                .taskId(existing.getTaskId())
                .status(existing.getStatus())
                .traceId(existing.getTraceId())
                .duplicated(true)
                .build();
    }

    private void publish(TaskInstance instance, TaskEvent.Type type, String status, String message) {
        eventPublisher.publishEvent(TaskEvent.of(instance.getTaskId(), instance.getTaskType(), instance.getMode(),
                type, null, null, status, message, instance.getJobId()));
    }
}
