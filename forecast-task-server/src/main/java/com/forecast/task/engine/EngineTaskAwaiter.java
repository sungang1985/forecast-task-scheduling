package com.forecast.task.engine;

import com.forecast.task.config.ForecastProperties;
import com.forecast.task.engine.dto.EngineDtos.EngineTaskStatus;
import com.forecast.task.pipeline.PipelineContext;
import com.forecast.task.pipeline.TaskCancelledException;
import com.forecast.task.pipeline.TaskTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 等待 Python 异步任务完成：指数退避轮询状态，同步引擎侧进度，
 * 任务取消/超时时主动通知引擎取消，避免引擎资源空耗。
 */
@Slf4j
@Component
public class EngineTaskAwaiter {

    private final PythonEngineClient client;
    private final ForecastProperties properties;

    public EngineTaskAwaiter(PythonEngineClient client, ForecastProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public EngineTaskStatus await(PipelineContext ctx, EngineEndpoint endpoint, String engineTaskId) {
        ctx.setCurrentEngineTaskId(engineTaskId);
        long interval = properties.getEngine().getPollInitialInterval().toMillis();
        long maxInterval = properties.getEngine().getPollMaxInterval().toMillis();
        Integer lastProgress = null;
        while (true) {
            try {
                ctx.checkActive();
            } catch (TaskCancelledException | TaskTimeoutException e) {
                cancelQuietly(endpoint, engineTaskId);
                throw e;
            }
            EngineTaskStatus status = client.getTask(endpoint, engineTaskId);
            if (status.progress() != null && !status.progress().equals(lastProgress)) {
                lastProgress = status.progress();
                ctx.reportProgress(status.progress(), status.message());
            }
            if (status.isTerminal()) {
                if (status.isSuccess()) {
                    return status;
                }
                String error = status.error() == null ? status.message() : status.error();
                throw new EngineTaskFailedException(engineTaskId,
                        "Python 引擎任务 " + engineTaskId + " " + status.status() + ": " + error);
            }
            sleep(interval, ctx, endpoint, engineTaskId);
            interval = Math.min(maxInterval, (long) (interval * 1.5));
        }
    }

    private void sleep(long millis, PipelineContext ctx, EngineEndpoint endpoint, String engineTaskId) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelQuietly(endpoint, engineTaskId);
            throw new TaskCancelledException("任务线程被中断");
        }
    }

    private void cancelQuietly(EngineEndpoint endpoint, String engineTaskId) {
        try {
            client.cancelTask(endpoint, engineTaskId);
        } catch (RuntimeException e) {
            log.warn("通知 Python 引擎取消任务 {} 失败: {}", engineTaskId, e.getMessage());
        }
    }
}
