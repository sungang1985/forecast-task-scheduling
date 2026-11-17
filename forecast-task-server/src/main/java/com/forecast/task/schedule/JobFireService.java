package com.forecast.task.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.forecast.api.dto.ForecastTaskRequest;
import com.forecast.api.dto.TaskSubmitResult;
import com.forecast.common.enums.ForecastMode;
import com.forecast.common.enums.TaskStatus;
import com.forecast.common.enums.TriggerType;
import com.forecast.common.util.JsonUtils;
import com.forecast.task.config.ExecutorConfig;
import com.forecast.task.config.NodeIdentity;
import com.forecast.task.domain.entity.JobConfig;
import com.forecast.task.domain.entity.JobFireLog;
import com.forecast.task.domain.entity.TaskInstance;
import com.forecast.task.mapper.JobFireLogMapper;
import com.forecast.task.monitor.TaskEvent;
import com.forecast.task.service.ForecastTaskService;
import com.forecast.task.service.SubmitOptions;
import com.forecast.task.service.TaskRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 定时任务触发执行：
 * <ul>
 *     <li>集群去重：以 (job_id, fire_time, attempt) 唯一键抢占触发记录，多实例部署时同一触发点只会执行一次；</li>
 *     <li>并发策略：SKIP 时上一次任务未结束则跳过本次；</li>
 *     <li>参数模板：按触发时间计算本次任务的历史/预测时间窗口；</li>
 *     <li>失败重试：任务失败后按配置的次数与间隔重新触发；</li>
 *     <li>结果回写：任务结束后回写触发记录与定时任务的最近状态（预测结果由流程中的 PERSIST_RESULT 入库）。</li>
 * </ul>
 */
@Slf4j
@Service
public class JobFireService {

    private final JobConfigService jobConfigService;
    private final JobFireLogMapper fireLogMapper;
    private final ForecastTaskService taskService;
    private final TaskRecordService recordService;
    private final NodeIdentity nodeIdentity;
    private final TaskScheduler scheduler;

    public JobFireService(JobConfigService jobConfigService, JobFireLogMapper fireLogMapper,
                          ForecastTaskService taskService, TaskRecordService recordService, NodeIdentity nodeIdentity,
                          @Qualifier(ExecutorConfig.JOB_SCHEDULER) TaskScheduler scheduler) {
        this.jobConfigService = jobConfigService;
        this.fireLogMapper = fireLogMapper;
        this.taskService = taskService;
        this.recordService = recordService;
        this.nodeIdentity = nodeIdentity;
        this.scheduler = scheduler;
    }

    /**
     * 触发一次定时任务。
     *
     * @param fireTime     计划触发时间（任务时区）
     * @param attempt      0 为首次触发，>0 为失败重试
     * @param parentTaskId 重试时的来源任务
     * @return 抢占成功并已处理时返回触发记录；被其他节点抢占时返回 empty
     */
    public Optional<JobFireLog> fire(JobConfig job, ZonedDateTime fireTime, int attempt, TriggerType trigger,
                                     String parentTaskId) {
        LocalDateTime fireLocal = fireTime.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
                .truncatedTo(ChronoUnit.MILLIS);
        JobFireLog fireLog = new JobFireLog();
        fireLog.setJobId(job.getId());
        fireLog.setFireTime(fireLocal);
        fireLog.setAttempt(attempt);
        fireLog.setTriggerType(trigger.name());
        fireLog.setActualFireTime(LocalDateTime.now());
        fireLog.setNode(nodeIdentity.getNodeId());
        fireLog.setStatus(JobFireLog.TRIGGERED);
        try {
            fireLogMapper.insert(fireLog);
        } catch (DuplicateKeyException e) {
            log.debug("定时任务 {} 触发点 {} attempt={} 已由其他节点处理", job.getJobCode(), fireLocal, attempt);
            return Optional.empty();
        }

        if (attempt == 0 && JobConfig.POLICY_SKIP.equals(job.getConcurrentPolicy()) && hasActiveTask(job.getId())) {
            fireLog.setStatus(JobFireLog.SKIPPED);
            fireLog.setMessage("上一次任务尚未结束，按 SKIP 策略跳过");
            fireLogMapper.updateById(fireLog);
            jobConfigService.updateFireState(job.getId(), fireLocal, null, null, JobFireLog.SKIPPED);
            log.warn("定时任务 {} 触发点 {} 跳过：上一次任务尚未结束", job.getJobCode(), fireLocal);
            return Optional.of(fireLog);
        }

        try {
            JobTaskTemplate template = StringUtils.hasText(job.getTaskParams())
                    ? JsonUtils.mapper().readValue(job.getTaskParams(), JobTaskTemplate.class) : new JobTaskTemplate();
            String requestId = "JOB-" + job.getId() + "-" + fireLocal.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    + "-" + attempt;
            ForecastTaskRequest request = template.toRequest(fireTime.toLocalDateTime(), job.getTimeoutSeconds(), requestId);
            TaskSubmitResult result = taskService.submit(job.getTaskType(), ForecastMode.parse(job.getMode()), request,
                    new SubmitOptions(trigger, job.getId(), fireLocal, parentTaskId, attempt));
            fireLog.setTaskId(result.getTaskId());
            fireLogMapper.updateById(fireLog);
            jobConfigService.updateFireState(job.getId(), fireLocal, null, result.getTaskId(), result.getStatus());
            log.info("定时任务 {} 已触发 fireTime={} attempt={} taskId={}", job.getJobCode(), fireLocal, attempt,
                    result.getTaskId());
        } catch (Exception e) {
            fireLog.setStatus(JobFireLog.SUBMIT_FAILED);
            fireLog.setMessage(truncate(e.getMessage()));
            fireLogMapper.updateById(fireLog);
            jobConfigService.updateFireState(job.getId(), fireLocal, null, null, JobFireLog.SUBMIT_FAILED);
            log.error("定时任务 {} 提交失败 fireTime={}: {}", job.getJobCode(), fireLocal, e.getMessage());
            scheduleRetryIfNeeded(job, fireTime, attempt, null);
        }
        return Optional.of(fireLog);
    }

    /**
     * 任务结束：回写触发记录与定时任务状态，失败时按配置重试。
     */
    @EventListener
    public void onTaskFinished(TaskEvent event) {
        if (!event.isFinished() || event.jobId() == null) {
            return;
        }
        JobFireLog fireLog = fireLogMapper.selectOne(Wrappers.<JobFireLog>lambdaQuery()
                .eq(JobFireLog::getTaskId, event.taskId()));
        if (fireLog == null) {
            return;
        }
        fireLog.setStatus(event.status());
        fireLog.setMessage(truncate(event.message()));
        fireLogMapper.updateById(fireLog);

        JobConfig job = jobConfigService.find(event.jobId());
        if (job == null) {
            return;
        }
        if (event.taskId().equals(job.getLastTaskId())) {
            jobConfigService.updateFireState(job.getId(), null, null, null, event.status());
        }
        if (!TaskStatus.SUCCESS.name().equals(event.status()) && !TaskStatus.CANCELLED.name().equals(event.status())) {
            ZonedDateTime fireTime = fireLog.getFireTime().atZone(ZoneId.systemDefault())
                    .withZoneSameInstant(JobConfigService.zoneOf(job.getTimeZone()));
            scheduleRetryIfNeeded(job, fireTime, fireLog.getAttempt(), event.taskId());
        }
    }

    private void scheduleRetryIfNeeded(JobConfig job, ZonedDateTime fireTime, int attempt, String parentTaskId) {
        int maxRetries = job.getMaxRetries() == null ? 0 : job.getMaxRetries();
        if (attempt >= maxRetries) {
            return;
        }
        int interval = job.getRetryIntervalSeconds() == null ? 300 : job.getRetryIntervalSeconds();
        Instant at = Instant.now().plusSeconds(interval);
        log.info("定时任务 {} 将在 {} 秒后进行第 {} 次重试", job.getJobCode(), interval, attempt + 1);
        scheduler.schedule(() -> {
            JobConfig latest = jobConfigService.find(job.getId());
            if (latest == null || !Boolean.TRUE.equals(latest.getEnabled())) {
                return;
            }
            fire(latest, fireTime, attempt + 1, TriggerType.RETRY, parentTaskId);
        }, at);
    }

    private boolean hasActiveTask(Long jobId) {
        return recordService.count(Wrappers.<TaskInstance>lambdaQuery()
                .eq(TaskInstance::getJobId, jobId)
                .in(TaskInstance::getStatus, TaskStatus.PENDING.name(), TaskStatus.RUNNING.name())) > 0;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 2000 ? s : s.substring(0, 2000);
    }
}
