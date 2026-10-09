package com.forecast.task.monitor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.forecast.common.enums.TaskStatus;
import com.forecast.task.config.NodeIdentity;
import com.forecast.task.domain.entity.TaskInstance;
import com.forecast.task.pipeline.PipelineContext;
import com.forecast.task.service.RunningTaskRegistry;
import com.forecast.task.service.TaskRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 任务运维后台作业：
 * <ul>
 *     <li>心跳：定期刷新本节点活动任务的心跳时间；</li>
 *     <li>取消感知：其他节点发起的取消请求在本节点生效；</li>
 *     <li>僵尸任务回收：心跳超时（节点宕机）的任务标记为失败，触发定时任务重试/告警；</li>
 *     <li>启动自愈：本节点上次异常退出遗留的活动任务标记为失败。</li>
 * </ul>
 */
@Slf4j
@Component
public class TaskMaintenanceJobs {

    private final RunningTaskRegistry runningRegistry;
    private final TaskRecordService recordService;
    private final NodeIdentity nodeIdentity;
    private final ApplicationEventPublisher eventPublisher;
    private final com.forecast.task.config.ForecastProperties properties;

    public TaskMaintenanceJobs(RunningTaskRegistry runningRegistry, TaskRecordService recordService,
                               NodeIdentity nodeIdentity, ApplicationEventPublisher eventPublisher,
                               com.forecast.task.config.ForecastProperties properties) {
        this.runningRegistry = runningRegistry;
        this.recordService = recordService;
        this.nodeIdentity = nodeIdentity;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphans() {
        List<TaskInstance> orphans = recordService.list(Wrappers.<TaskInstance>lambdaQuery()
                .eq(TaskInstance::getExecutorNode, nodeIdentity.getNodeId())
                .in(TaskInstance::getStatus, TaskStatus.PENDING.name(), TaskStatus.RUNNING.name()));
        for (TaskInstance t : orphans) {
            if (recordService.markFinished(t.getTaskId(), TaskStatus.FAILED, null, "EXECUTOR_RESTARTED",
                    "执行节点重启，任务中断", null)) {
                publishFinished(t, "执行节点重启，任务中断");
            }
        }
        if (!orphans.isEmpty()) {
            log.warn("节点 {} 启动时回收遗留任务 {} 个", nodeIdentity.getNodeId(), orphans.size());
        }
    }

    @Scheduled(fixedDelayString = "#{@forecastProperties.task.heartbeatInterval.toMillis()}")
    public void heartbeat() {
        Set<String> ids = runningRegistry.activeTaskIds();
        if (!ids.isEmpty()) {
            recordService.heartbeat(ids);
        }
    }

    @Scheduled(fixedDelayString = "#{@forecastProperties.task.cancelCheckInterval.toMillis()}")
    public void syncCancellation() {
        List<String> runningIds = runningRegistry.runningContexts().stream()
                .filter(ctx -> !ctx.isCancelRequested())
                .map(PipelineContext::getTaskId).toList();
        if (runningIds.isEmpty()) {
            return;
        }
        for (String taskId : recordService.findCancelRequested(runningIds)) {
            PipelineContext ctx = runningRegistry.get(taskId);
            if (ctx != null) {
                log.info("感知到取消请求 taskId={}", taskId);
                ctx.requestCancel();
            }
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void reapStaleTasks() {
        List<TaskInstance> failed = recordService.failStaleTasks(properties.getTask().getStaleTimeout());
        for (TaskInstance t : failed) {
            log.warn("回收僵尸任务 taskId={} node={} lastHeartbeat={}", t.getTaskId(), t.getExecutorNode(),
                    t.getHeartbeatTime());
            publishFinished(t, "执行节点心跳超时");
        }
    }

    private void publishFinished(TaskInstance t, String message) {
        eventPublisher.publishEvent(new TaskEvent(t.getTaskId(), t.getTaskType(), t.getMode(), TaskEvent.Type.FINISHED,
                null, null, TaskStatus.FAILED.name(), message, t.getJobId(), java.util.Map.of(), LocalDateTime.now()));
    }
}
