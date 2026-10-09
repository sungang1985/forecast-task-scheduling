package com.forecast.task.controller;

import com.forecast.common.model.PageResult;
import com.forecast.common.model.R;
import com.forecast.task.domain.entity.JobConfig;
import com.forecast.task.domain.entity.JobFireLog;
import com.forecast.task.schedule.ForecastJobScheduler;
import com.forecast.task.schedule.JobConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 定时任务接口：维护任务配置（Cron + 任务参数模板），启停、立即触发、触发记录与调度状态。
 */
@Tag(name = "定时任务", description = "基于数据库配置的 Cron 定时预测任务")
@Validated
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobConfigService jobConfigService;
    private final ForecastJobScheduler scheduler;

    public JobController(JobConfigService jobConfigService, ForecastJobScheduler scheduler) {
        this.jobConfigService = jobConfigService;
        this.scheduler = scheduler;
    }

    @Operation(summary = "定时任务列表")
    @GetMapping
    public R<PageResult<JobConfig>> page(@RequestParam(required = false) String taskType,
                                         @RequestParam(required = false) Boolean enabled,
                                         @RequestParam(defaultValue = "1") @Min(1) long page,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(200) long size) {
        return R.ok(jobConfigService.page(taskType, enabled, page, size));
    }

    @Operation(summary = "定时任务详情")
    @GetMapping("/{id}")
    public R<JobConfig> get(@PathVariable Long id) {
        return R.ok(jobConfigService.require(id));
    }

    @Operation(summary = "新增定时任务", description = "cronExpression 为 Spring 6 位格式（秒 分 时 日 月 周）")
    @PostMapping
    public R<JobConfig> create(@RequestBody JobConfig job) {
        JobConfig created = jobConfigService.create(job);
        scheduler.refresh();
        return R.ok(created);
    }

    @Operation(summary = "修改定时任务")
    @PutMapping("/{id}")
    public R<JobConfig> update(@PathVariable Long id, @RequestBody JobConfig job) {
        JobConfig updated = jobConfigService.update(id, job);
        scheduler.refresh();
        return R.ok(updated);
    }

    @Operation(summary = "删除定时任务")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        jobConfigService.delete(id);
        scheduler.refresh();
        return R.ok();
    }

    @Operation(summary = "启用")
    @PostMapping("/{id}/enable")
    public R<Void> enable(@PathVariable Long id) {
        jobConfigService.setEnabled(id, true);
        scheduler.refresh();
        return R.ok();
    }

    @Operation(summary = "停用")
    @PostMapping("/{id}/disable")
    public R<Void> disable(@PathVariable Long id) {
        jobConfigService.setEnabled(id, false);
        scheduler.refresh();
        return R.ok();
    }

    @Operation(summary = "立即触发一次")
    @PostMapping("/{id}/trigger")
    public R<Void> trigger(@PathVariable Long id) {
        scheduler.triggerNow(id);
        return R.ok();
    }

    @Operation(summary = "触发记录")
    @GetMapping("/{id}/fire-logs")
    public R<PageResult<JobFireLog>> fireLogs(@PathVariable Long id,
                                              @RequestParam(defaultValue = "1") @Min(1) long page,
                                              @RequestParam(defaultValue = "20") @Min(1) @Max(200) long size) {
        return R.ok(jobConfigService.fireLogs(id, page, size));
    }

    @Operation(summary = "预览 Cron 未来触发时间")
    @GetMapping("/cron/preview")
    public R<List<LocalDateTime>> preview(@RequestParam String cron,
                                          @RequestParam(required = false) String timeZone,
                                          @RequestParam(defaultValue = "5") @Min(1) @Max(50) int count) {
        return R.ok(JobConfigService.preview(cron, timeZone, count));
    }

    @Operation(summary = "本节点调度状态")
    @GetMapping("/scheduler/status")
    public R<List<Map<String, Object>>> status() {
        return R.ok(scheduler.status());
    }
}
