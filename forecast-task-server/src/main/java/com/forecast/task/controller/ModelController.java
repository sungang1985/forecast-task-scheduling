package com.forecast.task.controller;

import com.forecast.common.model.PageResult;
import com.forecast.common.model.R;
import com.forecast.task.domain.entity.ForecastModel;
import com.forecast.task.service.ModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型版本管理：查询训练产出的模型及评估指标，切换预测使用的模型版本（回滚）。
 */
@Tag(name = "模型管理", description = "模型版本查询与切换")
@Validated
@RestController
@RequestMapping("/api/v1/models")
public class ModelController {

    private final ModelService modelService;

    public ModelController(ModelService modelService) {
        this.modelService = modelService;
    }

    @Operation(summary = "模型列表")
    @GetMapping
    public R<PageResult<ForecastModel>> page(@RequestParam(required = false) String taskType,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "1") @Min(1) long page,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(200) long size) {
        return R.ok(modelService.page(taskType == null ? null : taskType.toUpperCase(), status, page, size));
    }

    @Operation(summary = "模型详情")
    @GetMapping("/{modelId}")
    public R<ForecastModel> get(@PathVariable String modelId) {
        return R.ok(modelService.require(modelId));
    }

    @Operation(summary = "设为当前可用版本（同类模型其余版本置为 INACTIVE）")
    @PostMapping("/{modelId}/activate")
    public R<ForecastModel> activate(@PathVariable String modelId) {
        return R.ok(modelService.activate(modelId));
    }
}
