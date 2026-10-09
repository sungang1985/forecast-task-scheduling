package com.forecast.task.schedule;

import com.forecast.common.enums.TriggerType;
import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import com.forecast.task.config.ExecutorConfig;
import com.forecast.task.config.ForecastProperties;
import com.forecast.task.domain.entity.JobConfig;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 基于数据库配置的动态 Cron 调度器。
 * <ul>
 *     <li>启动时及每个刷新周期从 forecast_job_config 读取配置，新增/修改/停用/删除自动生效，无需重启；</li>
 *     <li>按 Cron 精确计算下一次触发时间并单次调度（触发后再计算下一次），触发时间点在集群内一致，
 *     配合触发记录唯一键实现多实例去重；</li>
 *     <li>FIRE_ONCE 策略：服务停机期间错过的触发在启动后补偿执行一次。</li>
 * </ul>
 */
@Slf4j
@Component
public class ForecastJobScheduler {

    private final JobConfigService jobConfigService;
    private final JobFireService fireService;
    private final TaskScheduler scheduler;
    private final ForecastProperties properties;
    private final Map<Long, Registration> registrations = new ConcurrentHashMap<>();
    private volatile ScheduledFuture<?> refresher;

    private record Registration(String fingerprint, ScheduledFuture<?> future, ZonedDateTime nextFireTime) {
    }

    public ForecastJobScheduler(JobConfigService jobConfigService, JobFireService fireService,
                                @Qualifier(ExecutorConfig.JOB_SCHEDULER) TaskScheduler scheduler,
                                ForecastProperties properties) {
        this.jobConfigService = jobConfigService;
        this.fireService = fireService;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.getScheduler().isEnabled()) {
            log.info("定时调度已关闭（forecast.scheduler.enabled=false），本节点仅提供接口服务");
            return;
        }
        refresh();
        Duration interval = properties.getScheduler().getRefreshInterval();
        refresher = scheduler.scheduleWithFixedDelay(this::refreshQuietly,
                java.time.Instant.now().plus(interval), interval);
        log.info("定时调度已启动，已注册 {} 个任务，配置刷新间隔 {}", registrations.size(), interval);
    }

    @PreDestroy
    public void stop() {
        if (refresher != null) {
            refresher.cancel(false);
        }
        registrations.values().forEach(r -> r.future().cancel(false));
        registrations.clear();
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (RuntimeException e) {
            log.error("刷新定时任务配置失败", e);
        }
    }

    /**
     * 与数据库配置对齐。
     */
    public synchronized void refresh() {
        if (!properties.getScheduler().isEnabled()) {
            return;
        }
        Set<Long> seen = new HashSet<>();
        for (JobConfig job : jobConfigService.listAll()) {
            seen.add(job.getId());
            Registration current = registrations.get(job.getId());
            if (!Boolean.TRUE.equals(job.getEnabled())) {
                if (current != null) {
                    unregister(job.getId());
                    log.info("定时任务 {} 已停用，取消调度", job.getJobCode());
                }
                continue;
            }
            if (current != null && current.fingerprint().equals(job.scheduleFingerprint())) {
                continue;
            }
            try {
                if (current != null) {
                    unregister(job.getId());
                } else {
                    handleMisfire(job);
                }
                scheduleNext(job, ZonedDateTime.now(JobConfigService.zoneOf(job.getTimeZone())));
                log.info("定时任务 {}({}) 已注册 cron=[{}] 下次触发 {}", job.getJobCode(), job.getJobName(),
                        job.getCronExpression(), registrations.get(job.getId()) == null ? "无"
                                : registrations.get(job.getId()).nextFireTime());
            } catch (BizException e) {
                log.error("定时任务 {} 配置非法，跳过注册: {}", job.getJobCode(), e.getMessage());
            }
        }
        new ArrayList<>(registrations.keySet()).stream()
                .filter(id -> !seen.contains(id))
                .forEach(id -> {
                    unregister(id);
                    log.info("定时任务 {} 已删除，取消调度", id);
                });
    }

    private void scheduleNext(JobConfig job, ZonedDateTime after) {
        CronExpression cron = JobConfigService.parseCron(job.getCronExpression());
        ZonedDateTime next = cron.next(after);
        if (next == null) {
            registrations.remove(job.getId());
            return;
        }
        String fingerprint = job.scheduleFingerprint();
        ScheduledFuture<?> future = scheduler.schedule(() -> onFire(job.getId(), fingerprint, next), next.toInstant());
        registrations.put(job.getId(), new Registration(fingerprint, future, next));
        jobConfigService.updateFireState(job.getId(), null,
                next.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(), null, null);
    }

    private void onFire(Long jobId, String fingerprint, ZonedDateTime fireTime) {
        Registration registration = registrations.get(jobId);
        if (registration == null || !registration.fingerprint().equals(fingerprint)) {
            return;
        }
        JobConfig job = jobConfigService.find(jobId);
        if (job == null || !Boolean.TRUE.equals(job.getEnabled()) || !fingerprint.equals(job.scheduleFingerprint())) {
            // 配置已变化，由下一次刷新重新注册
            registrations.remove(jobId);
            return;
        }
        // 先排下一次，保证触发链不中断
        scheduleNext(job, fireTime);
        fireService.fire(job, fireTime, 0, TriggerType.SCHEDULE, null);
    }

    /**
     * 停机期间错过的触发：FIRE_ONCE 策略下补偿执行最近一次。
     */
    private void handleMisfire(JobConfig job) {
        if (!JobConfig.MISFIRE_FIRE_ONCE.equals(job.getMisfirePolicy()) || job.getLastFireTime() == null) {
            return;
        }
        ZoneId zone = JobConfigService.zoneOf(job.getTimeZone());
        CronExpression cron = JobConfigService.parseCron(job.getCronExpression());
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime threshold = now.minus(properties.getScheduler().getMisfireThreshold());
        ZonedDateTime cursor = job.getLastFireTime().atZone(ZoneId.systemDefault()).withZoneSameInstant(zone);
        ZonedDateTime missed = null;
        for (int i = 0; i < 10_000; i++) {
            ZonedDateTime next = cron.next(cursor);
            if (next == null || !next.isBefore(threshold)) {
                break;
            }
            missed = next;
            cursor = next;
        }
        if (missed != null) {
            ZonedDateTime fireTime = missed;
            log.warn("定时任务 {} 错过触发 {}，按 FIRE_ONCE 策略补偿执行", job.getJobCode(), fireTime);
            scheduler.schedule(() -> fireService.fire(job, fireTime, 0, TriggerType.SCHEDULE, null),
                    java.time.Instant.now());
        }
    }

    /**
     * 手动立即触发。
     */
    public void triggerNow(Long jobId) {
        JobConfig job = jobConfigService.require(jobId);
        ZonedDateTime now = ZonedDateTime.now(JobConfigService.zoneOf(job.getTimeZone())).truncatedTo(ChronoUnit.SECONDS);
        if (fireService.fire(job, now, 0, TriggerType.MANUAL, null).isEmpty()) {
            throw new BizException(ErrorCode.CONFLICT, "该时刻已触发过，请稍后重试");
        }
    }

    private void unregister(Long jobId) {
        Registration removed = registrations.remove(jobId);
        if (removed != null) {
            removed.future().cancel(false);
        }
    }

    /** 本节点调度状态 */
    public List<Map<String, Object>> status() {
        List<Map<String, Object>> list = new ArrayList<>();
        registrations.forEach((id, r) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("jobId", String.valueOf(id));
            item.put("nextFireTime", r.nextFireTime().withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
            item.put("fingerprint", r.fingerprint());
            list.add(item);
        });
        return list;
    }

    public int registeredCount() {
        return registrations.size();
    }
}
