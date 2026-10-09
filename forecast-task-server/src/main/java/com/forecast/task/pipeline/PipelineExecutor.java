package com.forecast.task.pipeline;

import com.forecast.common.enums.StepStatus;
import com.forecast.common.exception.BizException;
import com.forecast.task.config.ForecastProperties;
import com.forecast.task.engine.EngineCallException;
import com.forecast.task.engine.EngineTaskFailedException;
import com.forecast.task.monitor.TaskEvent;
import com.forecast.task.service.TaskRecordService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.List;

/**
 * 流程执行器：按序执行步骤，负责步骤级重试、跳过、取消/超时检查、执行轨迹记录、观测（指标 + 链路）与资源清理。
 */
@Slf4j
@Component
public class PipelineExecutor {

    private final TaskRecordService recordService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObservationRegistry observationRegistry;
    private final ForecastProperties properties;

    public PipelineExecutor(TaskRecordService recordService, ApplicationEventPublisher eventPublisher,
                            ObservationRegistry observationRegistry, ForecastProperties properties) {
        this.recordService = recordService;
        this.eventPublisher = eventPublisher;
        this.observationRegistry = observationRegistry;
        this.properties = properties;
    }

    public void execute(PipelineContext ctx, List<ForecastStep> steps) {
        ctx.setStepCount(steps.size());
        try {
            for (int i = 0; i < steps.size(); i++) {
                ForecastStep step = steps.get(i);
                ctx.checkActive();
                ctx.setStepIndex(i);
                ctx.setCurrentStep(step.name());
                int order = i + 1;
                if (step.shouldSkip(ctx)) {
                    recordService.stepSkipped(ctx.getTaskId(), order, step.name(), "上下文已具备该步骤产物，跳过");
                    publish(ctx, TaskEvent.Type.STEP_FINISHED, step.name(), StepStatus.SKIPPED.name(), "skipped");
                    continue;
                }
                runStep(ctx, step, order);
                int progress = (int) ((long) (i + 1) * 100 / steps.size());
                recordService.updateProgress(ctx.getTaskId(), Math.min(progress, 99), step.name(), true);
            }
        } finally {
            runCleanups(ctx);
        }
    }

    private void runStep(PipelineContext ctx, ForecastStep step, int order) {
        int maxAttempts = step.retryable() ? Math.max(1, properties.getTask().getStepMaxAttempts()) : 1;
        for (int attempt = 1; ; attempt++) {
            ctx.checkActive();
            Long stepId = recordService.stepStarted(ctx.getTaskId(), order, step.name(), attempt);
            recordService.updateProgress(ctx.getTaskId(), ctx.getStepIndex() * 100 / ctx.getStepCount(), step.name(), true);
            publish(ctx, TaskEvent.Type.STEP_STARTED, step.name(), StepStatus.RUNNING.name(), step.description());
            long begin = System.currentTimeMillis();
            ctx.setCurrentEngineTaskId(null);
            try {
                StepResult result = Observation.createNotStarted("forecast.task.step", observationRegistry)
                        .contextualName("step " + step.name())
                        .lowCardinalityKeyValue("task.type", ctx.getTaskType())
                        .lowCardinalityKeyValue("step", step.name())
                        .observeChecked(() -> step.execute(ctx));
                long cost = System.currentTimeMillis() - begin;
                recordService.stepFinished(stepId, StepStatus.SUCCESS, result.message(), result.output(),
                        result.engineTaskId(), cost);
                publish(ctx, TaskEvent.Type.STEP_FINISHED, step.name(), StepStatus.SUCCESS.name(), result.message());
                log.info("步骤完成 step={} attempt={} cost={}ms {}", step.name(), attempt, cost, result.message());
                return;
            } catch (TaskCancelledException | TaskTimeoutException e) {
                recordService.stepFinished(stepId, StepStatus.CANCELLED, e.getMessage(), null,
                        ctx.getCurrentEngineTaskId(), System.currentTimeMillis() - begin);
                publish(ctx, TaskEvent.Type.STEP_FINISHED, step.name(), StepStatus.CANCELLED.name(), e.getMessage());
                throw e;
            } catch (Throwable e) {
                long cost = System.currentTimeMillis() - begin;
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                recordService.stepFinished(stepId, StepStatus.FAILED, message, null, ctx.getCurrentEngineTaskId(), cost);
                publish(ctx, TaskEvent.Type.STEP_FINISHED, step.name(), StepStatus.FAILED.name(), message);
                boolean retry = attempt < maxAttempts && isRetryable(e);
                log.warn("步骤失败 step={} attempt={}/{} retry={} cause={}", step.name(), attempt, maxAttempts, retry, message);
                if (!retry) {
                    if (e instanceof RuntimeException re) {
                        throw re;
                    }
                    if (e instanceof Error err) {
                        throw err;
                    }
                    throw new StepExecutionException(step.name(), e);
                }
                sleepBackoff(ctx, attempt);
            }
        }
    }

    /**
     * 业务性错误（参数非法、数据为空、模型不存在等）重试无意义；网络/引擎瞬时故障及未知异常允许重试。
     */
    private static boolean isRetryable(Throwable e) {
        if (e instanceof EngineCallException ece) {
            return ece.isRetryable();
        }
        if (e instanceof EngineTaskFailedException) {
            return false;
        }
        return !(e instanceof BizException);
    }

    private void sleepBackoff(PipelineContext ctx, int attempt) {
        long backoff = properties.getTask().getStepRetryBackoff().toMillis() * attempt;
        long until = System.currentTimeMillis() + backoff;
        while (System.currentTimeMillis() < until) {
            ctx.checkActive();
            try {
                Thread.sleep(Math.min(500, Math.max(1, until - System.currentTimeMillis())));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new TaskCancelledException("任务线程被中断");
            }
        }
    }

    private void runCleanups(PipelineContext ctx) {
        Deque<Runnable> cleanups = ctx.drainCleanups();
        for (Runnable cleanup : cleanups) {
            try {
                cleanup.run();
            } catch (RuntimeException e) {
                log.warn("任务资源清理失败（不影响任务结果）: {}", e.getMessage());
            }
        }
    }

    private void publish(PipelineContext ctx, TaskEvent.Type type, String step, String status, String message) {
        eventPublisher.publishEvent(TaskEvent.of(ctx.getTaskId(), ctx.getTaskType(), ctx.getMode().name(), type,
                step, null, status, message, ctx.getJobId()));
    }

    /**
     * 步骤抛出受检异常时的包装。
     */
    public static class StepExecutionException extends RuntimeException {
        public StepExecutionException(String step, Throwable cause) {
            super("步骤 " + step + " 执行失败: " + cause.getMessage(), cause);
        }
    }
}
