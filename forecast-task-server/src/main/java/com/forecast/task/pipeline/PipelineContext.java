package com.forecast.task.pipeline;

import com.forecast.api.dto.ForecastTaskRequest;
import com.forecast.common.enums.ForecastMode;
import com.forecast.task.config.ForecastProperties.TaskTypeProperties;
import com.forecast.task.engine.EngineEndpoint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 流程执行上下文：贯穿一次预测任务的全部步骤，承载请求参数、步骤间产物、取消/超时控制与进度汇报。
 */
@Getter
public class PipelineContext {

    private final String taskId;
    private final String taskType;
    private final ForecastMode mode;
    private final TaskTypeProperties taskTypeDef;
    /** 已补全默认值的请求 */
    private final ForecastTaskRequest request;
    private final Long jobId;
    private final Instant deadline;
    @Setter
    private String traceId;

    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    /** 对外展示的结果摘要（写入 result_json） */
    private final Map<String, Object> summary = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Deque<Runnable> cleanups = new ArrayDeque<>();

    private volatile EngineEndpoint engineEndpoint;
    @Setter
    private volatile String currentStep;
    @Setter
    private volatile int stepIndex;
    @Setter
    private volatile int stepCount = 1;
    @Setter
    private volatile String currentEngineTaskId;
    @Setter
    private ProgressListener progressListener;

    public PipelineContext(String taskId, String taskType, ForecastMode mode, TaskTypeProperties taskTypeDef,
                           ForecastTaskRequest request, Long jobId, Instant deadline) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.mode = mode;
        this.taskTypeDef = taskTypeDef;
        this.request = request;
        this.jobId = jobId;
        this.deadline = deadline;
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(PipelineContext ctx, int overallProgress, String message);
    }

    /**
     * 获取（首次时解析并固定）Python 引擎实例，保证同一任务的所有调用落在同一实例。
     */
    public EngineEndpoint engineEndpoint(Supplier<EngineEndpoint> resolver) {
        EngineEndpoint ep = engineEndpoint;
        if (ep == null) {
            synchronized (this) {
                if (engineEndpoint == null) {
                    engineEndpoint = resolver.get();
                }
                ep = engineEndpoint;
            }
        }
        return ep;
    }

    public void pinEngineEndpoint(EngineEndpoint endpoint) {
        this.engineEndpoint = endpoint;
    }

    public void put(String key, Object value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) attributes.get(key);
    }

    public boolean has(String key) {
        return attributes.containsKey(key);
    }

    public void summary(String key, Object value) {
        if (value != null) {
            summary.put(key, value);
        }
    }

    public synchronized void addCleanup(Runnable cleanup) {
        cleanups.push(cleanup);
    }

    public synchronized Deque<Runnable> drainCleanups() {
        Deque<Runnable> copy = new ArrayDeque<>(cleanups);
        cleanups.clear();
        return copy;
    }

    public void requestCancel() {
        cancelRequested.set(true);
    }

    public boolean isCancelRequested() {
        return cancelRequested.get();
    }

    /**
     * 检查取消与超时，步骤内部的长循环应周期性调用。
     */
    public void checkActive() {
        if (cancelRequested.get()) {
            throw new TaskCancelledException("任务已被取消");
        }
        if (deadline != null && Instant.now().isAfter(deadline)) {
            throw new TaskTimeoutException("任务执行超时（截止 " + deadline + "）");
        }
    }

    /**
     * 汇报当前步骤内的进度（0-100），自动折算为整体进度。
     */
    public void reportProgress(int stepPercent, String message) {
        if (progressListener == null) {
            return;
        }
        int bounded = Math.max(0, Math.min(100, stepPercent));
        int overall = (int) Math.min(99, ((long) stepIndex * 100 + bounded) / Math.max(1, stepCount));
        progressListener.onProgress(this, overall, message);
    }
}
