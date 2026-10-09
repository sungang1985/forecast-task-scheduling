package com.forecast.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预测任务提交请求（预处理 / 训练 / 预测 / 一体化预测通用）。
 * <p>
 * 未填写的时间窗口按任务类型配置的默认值推算：
 * historyEnd 默认等于 forecastStart（或当天 0 点），historyStart 默认向前推 defaultHistoryDays 天，
 * forecastEnd 默认 forecastStart + defaultHorizonHours。
 */
@Data
@Schema(description = "预测任务请求")
public class ForecastTaskRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "幂等请求号，同一 requestId 重复提交返回同一任务", example = "req-20260101-0001")
    @Size(max = 64)
    @Pattern(regexp = "^[A-Za-z0-9_.:-]*$", message = "requestId 仅允许字母、数字及 _ . : -")
    private String requestId;

    @Schema(description = "预测对象（区域编码 / 节点编码等），为空表示全部", example = "[\"AREA_01\"]")
    @Size(max = 500)
    private List<String> seriesKeys = new ArrayList<>();

    @Schema(description = "预测目标字段，为空取任务类型默认配置", example = "[\"load\"]")
    private List<String> targetFields = new ArrayList<>();

    @Schema(description = "历史数据开始时间（含）")
    private LocalDateTime historyStart;

    @Schema(description = "历史数据结束时间（不含）")
    private LocalDateTime historyEnd;

    @Schema(description = "预测开始时间（含）")
    private LocalDateTime forecastStart;

    @Schema(description = "预测结束时间（不含）")
    private LocalDateTime forecastEnd;

    @Schema(description = "算法：gbrt / rf / linear / naive，为空取默认", example = "gbrt")
    private String algorithm;

    @Schema(description = "预测时使用的模型 ID，为空则使用该任务类型最新可用模型")
    private String modelId;

    @Schema(description = "训练时直接使用已有的预处理数据集（跳过取数与预处理）")
    private String processedDatasetId;

    @Schema(description = "透传给 Python 引擎的算法参数")
    private Map<String, Object> params = new LinkedHashMap<>();

    @Schema(description = "任务结束后回调地址（需在白名单内）")
    @Size(max = 512)
    private String callbackUrl;

    @Schema(description = "任务超时时间（秒），为空取任务类型默认值")
    @Min(10)
    @Max(86400)
    private Integer timeoutSeconds;
}
