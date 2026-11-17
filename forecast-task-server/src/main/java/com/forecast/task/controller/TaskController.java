package com.forecast.task.controller;

import com.forecast.api.dto.ForecastPointVO;
import com.forecast.api.dto.TaskDetail;
import com.forecast.api.dto.TaskSubmitResult;
import com.forecast.common.enums.TaskStatus;
import com.forecast.common.model.PageResult;
import com.forecast.common.model.R;
import com.forecast.task.monitor.TaskEventStreamService;
import com.forecast.task.service.ForecastResultService;
import com.forecast.task.service.ForecastTaskService;
import com.forecast.task.service.TaskQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务监控与追踪：状态查询、步骤轨迹、实时进度、取消、重试、结果查询。
 */
@Tag(name = "任务管理", description = "任务状态、执行轨迹、实时进度、取消/重试、结果查询")
@Validated
@RestController
@RequestMapping("/api/v1")
public class TaskController {

    private final TaskQueryService queryService;
    private final ForecastTaskService taskService;
    private final ForecastResultService resultService;
    private final TaskEventStreamService streamService;

    public TaskController(TaskQueryService queryService, ForecastTaskService taskService,
                          ForecastResultService resultService, TaskEventStreamService streamService) {
        this.queryService = queryService;
        this.taskService = taskService;
        this.resultService = resultService;
        this.streamService = streamService;
    }

    @Operation(summary = "任务详情（含步骤执行轨迹）")
    @GetMapping("/tasks/{taskId}")
    public R<TaskDetail> get(@PathVariable String taskId) {
        return R.ok(queryService.detail(taskId));
    }

    @Operation(summary = "任务分页查询")
    @GetMapping("/tasks")
    public R<PageResult<TaskDetail>> page(@RequestParam(required = false) String taskType,
                                          @RequestParam(required = false) String mode,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String triggerType,
                                          @RequestParam(required = false) Long jobId,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                          @RequestParam(defaultValue = "1") @Min(1) long page,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(200) long size) {
        return R.ok(queryService.page(taskType, mode, status, triggerType, jobId, from, to, page, size));
    }

    @Operation(summary = "订阅任务实时进度（SSE）")
    @GetMapping(value = "/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String taskId) {
        TaskDetail detail = queryService.detail(taskId);
        return streamService.subscribe(taskId, detail, TaskStatus.valueOf(detail.getStatus()).isTerminal());
    }

    @Operation(summary = "取消任务")
    @PostMapping("/tasks/{taskId}/cancel")
    public R<Boolean> cancel(@PathVariable String taskId) {
        return R.ok(taskService.cancel(taskId));
    }

    @Operation(summary = "按原参数重新执行（生成新任务）")
    @PostMapping("/tasks/{taskId}/retry")
    public R<TaskSubmitResult> retry(@PathVariable String taskId) {
        return R.ok(taskService.retry(taskId));
    }

    @Operation(summary = "任务预测结果（分页）")
    @GetMapping("/tasks/{taskId}/results")
    public R<PageResult<ForecastPointVO>> results(@PathVariable String taskId,
                                                  @RequestParam(required = false) String seriesKey,
                                                  @RequestParam(required = false) String target,
                                                  @RequestParam(defaultValue = "1") @Min(1) long page,
                                                  @RequestParam(defaultValue = "500") @Min(1) @Max(1000) long size) {
        return R.ok(resultService.pageByTask(taskId, seriesKey, target, page, size));
    }

    @Operation(summary = "查询预测对象最新一次预测结果")
    @GetMapping("/results/latest")
    public R<List<ForecastPointVO>> latest(@RequestParam String taskType,
                                           @RequestParam String seriesKey,
                                           @RequestParam String target,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return R.ok(resultService.latest(taskType.toUpperCase(), seriesKey, target, start, end));
    }
}
