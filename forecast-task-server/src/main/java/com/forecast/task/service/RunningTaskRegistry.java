package com.forecast.task.service;

import com.forecast.task.pipeline.PipelineContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 本节点任务注册表：排队中 / 运行中的任务（用于心跳、取消、监控）以及同步等待者。
 */
@Component
public class RunningTaskRegistry {

    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final Map<String, PipelineContext> running = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> completions = new ConcurrentHashMap<>();

    public void queued(String taskId) {
        queued.add(taskId);
        completions.computeIfAbsent(taskId, k -> new CompletableFuture<>());
    }

    public void started(PipelineContext ctx) {
        queued.remove(ctx.getTaskId());
        running.put(ctx.getTaskId(), ctx);
    }

    public void finished(String taskId) {
        queued.remove(taskId);
        running.remove(taskId);
        CompletableFuture<Void> future = completions.remove(taskId);
        if (future != null) {
            future.complete(null);
        }
    }

    public PipelineContext get(String taskId) {
        return running.get(taskId);
    }

    public Collection<PipelineContext> runningContexts() {
        return running.values();
    }

    /** 本节点全部活动任务（排队 + 运行） */
    public Set<String> activeTaskIds() {
        Set<String> ids = new HashSet<>(queued);
        ids.addAll(running.keySet());
        return ids;
    }

    public int runningCount() {
        return running.size();
    }

    public int queuedCount() {
        return queued.size();
    }

    /**
     * 等待任务结束。
     *
     * @return true 表示已结束（或本节点无此任务的等待记录），false 表示等待超时
     */
    public boolean await(String taskId, long seconds) {
        CompletableFuture<Void> future = completions.get(taskId);
        if (future == null) {
            return true;
        }
        try {
            future.get(seconds, TimeUnit.SECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return true;
        }
    }
}
