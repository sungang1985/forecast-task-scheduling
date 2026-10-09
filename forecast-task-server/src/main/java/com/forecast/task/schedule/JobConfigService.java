package com.forecast.task.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forecast.common.enums.ForecastMode;
import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import com.forecast.common.model.PageResult;
import com.forecast.common.util.JsonUtils;
import com.forecast.task.domain.entity.JobConfig;
import com.forecast.task.domain.entity.JobFireLog;
import com.forecast.task.mapper.JobConfigMapper;
import com.forecast.task.mapper.JobFireLogMapper;
import com.forecast.task.pipeline.TaskTypeRegistry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 定时任务配置管理（增删改查 + 校验）。修改后通知调度器立即刷新，其他节点在刷新周期内同步。
 */
@Service
public class JobConfigService {

    private final JobConfigMapper jobMapper;
    private final JobFireLogMapper fireLogMapper;
    private final TaskTypeRegistry taskTypeRegistry;

    public JobConfigService(JobConfigMapper jobMapper, JobFireLogMapper fireLogMapper, TaskTypeRegistry taskTypeRegistry) {
        this.jobMapper = jobMapper;
        this.fireLogMapper = fireLogMapper;
        this.taskTypeRegistry = taskTypeRegistry;
    }

    public List<JobConfig> listAll() {
        return jobMapper.selectList(Wrappers.<JobConfig>lambdaQuery().orderByAsc(JobConfig::getId));
    }

    public JobConfig find(Long id) {
        return jobMapper.selectById(id);
    }

    public JobConfig require(Long id) {
        JobConfig job = jobMapper.selectById(id);
        if (job == null) {
            throw new BizException(ErrorCode.JOB_NOT_FOUND, "定时任务不存在: " + id);
        }
        return job;
    }

    public PageResult<JobConfig> page(String taskType, Boolean enabled, long page, long size) {
        Page<JobConfig> result = jobMapper.selectPage(Page.of(page, size), Wrappers.<JobConfig>lambdaQuery()
                .eq(StringUtils.hasText(taskType), JobConfig::getTaskType, TaskTypeRegistry.normalize(taskType))
                .eq(enabled != null, JobConfig::getEnabled, enabled)
                .orderByAsc(JobConfig::getId));
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    public JobConfig create(JobConfig job) {
        job.setId(null);
        normalizeAndValidate(job);
        job.setVersion(0);
        try {
            jobMapper.insert(job);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.JOB_CODE_DUPLICATED, "定时任务编码已存在: " + job.getJobCode());
        }
        return job;
    }

    public JobConfig update(Long id, JobConfig job) {
        JobConfig existing = require(id);
        job.setId(id);
        job.setVersion(existing.getVersion());
        normalizeAndValidate(job);
        if (jobMapper.updateById(job) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "定时任务已被其他人修改，请刷新后重试");
        }
        return require(id);
    }

    public void setEnabled(Long id, boolean enabled) {
        JobConfig job = require(id);
        job.setEnabled(enabled);
        if (jobMapper.updateById(job) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "定时任务已被其他人修改，请刷新后重试");
        }
    }

    public void delete(Long id) {
        require(id);
        jobMapper.deleteById(id);
    }

    public void updateFireState(Long jobId, LocalDateTime lastFireTime, LocalDateTime nextFireTime, String lastTaskId,
                                String lastStatus) {
        jobMapper.update(null, Wrappers.<JobConfig>lambdaUpdate()
                .set(lastFireTime != null, JobConfig::getLastFireTime, lastFireTime)
                .set(nextFireTime != null, JobConfig::getNextFireTime, nextFireTime)
                .set(lastTaskId != null, JobConfig::getLastTaskId, lastTaskId)
                .set(lastStatus != null, JobConfig::getLastStatus, lastStatus)
                .eq(JobConfig::getId, jobId));
    }

    public PageResult<JobFireLog> fireLogs(Long jobId, long page, long size) {
        Page<JobFireLog> result = fireLogMapper.selectPage(Page.of(page, size), Wrappers.<JobFireLog>lambdaQuery()
                .eq(JobFireLog::getJobId, jobId)
                .orderByDesc(JobFireLog::getFireTime, JobFireLog::getAttempt));
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    /**
     * 预览 Cron 未来 N 次触发时间。
     */
    public static List<LocalDateTime> preview(String cron, String timeZone, int count) {
        CronExpression expression = parseCron(cron);
        ZoneId zone = zoneOf(timeZone);
        List<LocalDateTime> times = new ArrayList<>();
        ZonedDateTime cursor = ZonedDateTime.now(zone);
        for (int i = 0; i < Math.min(Math.max(count, 1), 50); i++) {
            cursor = expression.next(cursor);
            if (cursor == null) {
                break;
            }
            times.add(cursor.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
        }
        return times;
    }

    public static CronExpression parseCron(String cron) {
        try {
            return CronExpression.parse(cron);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BizException(ErrorCode.JOB_CRON_INVALID,
                    "Cron 表达式非法（需 6 位：秒 分 时 日 月 周）: " + cron);
        }
    }

    public static ZoneId zoneOf(String timeZone) {
        if (!StringUtils.hasText(timeZone)) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(timeZone);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "时区非法: " + timeZone);
        }
    }

    private void normalizeAndValidate(JobConfig job) {
        if (!StringUtils.hasText(job.getJobCode()) || !StringUtils.hasText(job.getJobName())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "jobCode、jobName 不能为空");
        }
        job.setTaskType(TaskTypeRegistry.normalize(job.getTaskType()));
        taskTypeRegistry.require(job.getTaskType());
        if (!StringUtils.hasText(job.getMode())) {
            job.setMode(ForecastMode.FULL.name());
        }
        try {
            job.setMode(ForecastMode.parse(job.getMode()).name());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "mode 非法: " + job.getMode());
        }
        parseCron(job.getCronExpression());
        zoneOf(job.getTimeZone());
        if (StringUtils.hasText(job.getTaskParams())) {
            try {
                JsonUtils.mapper().readValue(job.getTaskParams(), JobTaskTemplate.class);
            } catch (Exception e) {
                throw new BizException(ErrorCode.BAD_REQUEST, "taskParams 不是合法的参数模板 JSON: " + e.getMessage());
            }
        }
        job.setConcurrentPolicy(StringUtils.hasText(job.getConcurrentPolicy())
                ? job.getConcurrentPolicy().toUpperCase(Locale.ROOT) : JobConfig.POLICY_SKIP);
        if (!List.of(JobConfig.POLICY_SKIP, JobConfig.POLICY_ALLOW).contains(job.getConcurrentPolicy())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "concurrentPolicy 仅支持 SKIP / ALLOW");
        }
        job.setMisfirePolicy(StringUtils.hasText(job.getMisfirePolicy())
                ? job.getMisfirePolicy().toUpperCase(Locale.ROOT) : JobConfig.MISFIRE_IGNORE);
        if (!List.of(JobConfig.MISFIRE_IGNORE, JobConfig.MISFIRE_FIRE_ONCE).contains(job.getMisfirePolicy())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "misfirePolicy 仅支持 IGNORE / FIRE_ONCE");
        }
        if (job.getEnabled() == null) {
            job.setEnabled(true);
        }
        if (job.getMaxRetries() == null || job.getMaxRetries() < 0) {
            job.setMaxRetries(0);
        }
        if (job.getRetryIntervalSeconds() == null || job.getRetryIntervalSeconds() < 1) {
            job.setRetryIntervalSeconds(300);
        }
    }
}
