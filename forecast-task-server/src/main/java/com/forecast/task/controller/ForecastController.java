package com.forecast.task.controller;

import com.forecast.api.dto.ForecastTaskRequest;
import com.forecast.api.dto.TaskSubmitResult;
import com.forecast.common.enums.ForecastMode;
import com.forecast.common.model.R;
import com.forecast.task.config.ForecastProperties.TaskTypeProperties;
import com.forecast.task.pipeline.PipelineRegistry;
import com.forecast.task.pipeline.TaskTypeRegistry;
import com.forecast.task.service.ForecastTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对外预测接口：量价（PRICE）、负荷（LOAD）等任务类型 × 预处理 / 训练 / 预测 / 一体化预测。
 * 默认异步：立即返回 taskId，通过任务接口查询进度与结果；waitSeconds>0 时在该时间内同步等待。
 */
@Tag(name = "预测任务", description = "数据预处理、模型训练、结果预测、一体化预测")
@RestController
@RequestMapping("/api/v1/forecast")
public class ForecastController {

    private final ForecastTaskService taskService;
    private final TaskTypeRegistry taskTypeRegistry;
    private final PipelineRegistry pipelineRegistry;

    public ForecastController(ForecastTaskService taskService, TaskTypeRegistry taskTypeRegistry,
                              PipelineRegistry pipelineRegistry) {
        this.taskService = taskService;
        this.taskTypeRegistry = taskTypeRegistry;
        this.pipelineRegistry = pipelineRegistry;
    }

    @Operation(summary = "数据预处理", description = "取数（多数据源、缓存优先）→ 分批传输 → 清洗/对齐/特征工程，返回预处理数据集 ID")
    @PostMapping("/{taskType}/preprocess")
    public R<TaskSubmitResult> preprocess(@Parameter(example = "LOAD") @PathVariable String taskType,
                                          @RequestParam(defaultValue = "0") int waitSeconds,
                                          @Valid @RequestBody ForecastTaskRequest request) {
        return R.ok(taskService.submitAndWait(taskType, ForecastMode.PREPROCESS, request, waitSeconds));
    }

    @Operation(summary = "模型训练", description = "可指定 processedDatasetId 复用预处理结果，否则自动取数并预处理")
    @PostMapping("/{taskType}/train")
    public R<TaskSubmitResult> train(@PathVariable String taskType,
                                     @RequestParam(defaultValue = "0") int waitSeconds,
                                     @Valid @RequestBody ForecastTaskRequest request) {
        return R.ok(taskService.submitAndWait(taskType, ForecastMode.TRAIN, request, waitSeconds));
    }

    @Operation(summary = "结果预测", description = "使用指定或最新可用模型预测，结果入库")
    @PostMapping("/{taskType}/predict")
    public R<TaskSubmitResult> predict(@PathVariable String taskType,
                                       @RequestParam(defaultValue = "0") int waitSeconds,
                                       @Valid @RequestBody ForecastTaskRequest request) {
        return R.ok(taskService.submitAndWait(taskType, ForecastMode.PREDICT, request, waitSeconds));
    }

    @Operation(summary = "一体化预测", description = "取数 → 预处理 → 训练 → 预测 → 结果入库")
    @PostMapping("/{taskType}/full")
    public R<TaskSubmitResult> full(@PathVariable String taskType,
                                    @RequestParam(defaultValue = "0") int waitSeconds,
                                    @Valid @RequestBody ForecastTaskRequest request) {
        return R.ok(taskService.submitAndWait(taskType, ForecastMode.FULL, request, waitSeconds));
    }

    @Operation(summary = "按模式提交（通用入口，供 Feign 客户端使用）")
    @PostMapping("/{taskType}/{mode}")
    public R<TaskSubmitResult> submit(@PathVariable String taskType, @PathVariable String mode,
                                      @RequestParam(defaultValue = "0") int waitSeconds,
                                      @Valid @RequestBody ForecastTaskRequest request) {
        return R.ok(taskService.submitAndWait(taskType, ForecastMode.parse(mode), request, waitSeconds));
    }

    @Operation(summary = "已配置的任务类型与流程")
    @GetMapping("/task-types")
    public R<Map<String, Object>> taskTypes() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, TaskTypeProperties> types = taskTypeRegistry.all();
        result.put("taskTypes", types);
        result.put("steps", pipelineRegistry.stepDescriptions());
        return R.ok(result);
    }
}
