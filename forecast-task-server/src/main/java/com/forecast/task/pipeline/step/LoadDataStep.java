package com.forecast.task.pipeline.step;

import com.forecast.api.dto.ForecastTaskRequest;
import com.forecast.common.enums.ForecastMode;
import com.forecast.task.config.ForecastProperties.ExogenousSource;
import com.forecast.task.config.ForecastProperties.TaskTypeProperties;
import com.forecast.task.engine.DatasetTransferService;
import com.forecast.task.engine.DatasetTransferService.PartRequest;
import com.forecast.task.engine.DatasetTransferService.TransferResult;
import com.forecast.task.engine.EngineEndpoint;
import com.forecast.task.pipeline.ContextKeys;
import com.forecast.task.pipeline.ForecastStep;
import com.forecast.task.pipeline.PipelineContext;
import com.forecast.task.pipeline.StepResult;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 取数并分批传输：从多个（不同表结构的）数据源读取预训练数据（优先命中缓存），分批上传到 Python 引擎。
 * <ul>
 *     <li>主数据源（预测目标）读取历史窗口；</li>
 *     <li>外生变量数据源（气象等）在预测类模式下额外覆盖预测窗口（如气象预报）。</li>
 * </ul>
 */
@Component
public class LoadDataStep implements ForecastStep {

    public static final String NAME = "LOAD_DATA";

    private final DatasetTransferService transferService;
    private final EngineStepSupport support;

    public LoadDataStep(DatasetTransferService transferService, EngineStepSupport support) {
        this.transferService = transferService;
        this.support = support;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "读取预训练数据（多数据源、缓存优先）并分批传输到 Python 引擎";
    }

    @Override
    public boolean shouldSkip(PipelineContext ctx) {
        // 训练时直接复用已有的预处理数据集，无需取数
        return ctx.getMode() == ForecastMode.TRAIN && ctx.has(ContextKeys.PROCESSED_DATASET_ID);
    }

    @Override
    public StepResult execute(PipelineContext ctx) {
        ForecastTaskRequest req = ctx.getRequest();
        TaskTypeProperties def = ctx.getTaskTypeDef();
        boolean forecasting = ctx.getMode() == ForecastMode.PREDICT || ctx.getMode() == ForecastMode.FULL;

        List<PartRequest> parts = new ArrayList<>();
        parts.add(new PartRequest(DatasetTransferService.PRIMARY_PART, "target", def.getPrimarySource(),
                req.getSeriesKeys(), req.getHistoryStart(), req.getHistoryEnd(), true, true));
        for (ExogenousSource exo : def.getExogenousSources()) {
            LocalDateTime end = forecasting && exo.isCoverForecastWindow() ? req.getForecastEnd() : req.getHistoryEnd();
            parts.add(new PartRequest(exo.getSource(), "exogenous", exo.getSource(),
                    exo.isJoinOnKey() ? req.getSeriesKeys() : List.of(),
                    req.getHistoryStart(), end, exo.isJoinOnKey(), false));
        }

        EngineEndpoint endpoint = support.endpoint(ctx);
        TransferResult result = transferService.transfer(ctx, endpoint, parts);
        ctx.put(ContextKeys.DATASET_ID, result.datasetId());
        ctx.put(ContextKeys.DATA_ROWS, result.rows());
        ctx.summary("dataRows", result.rows());

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("datasetId", result.datasetId());
        output.put("rows", result.rows());
        output.put("chunks", result.chunks());
        output.put("bytes", result.bytes());
        output.put("historyWindow", req.getHistoryStart() + " ~ " + req.getHistoryEnd());
        output.put("engine", endpoint.baseUrl());
        return StepResult.of("已上传 " + result.rows().values().stream().mapToLong(Long::longValue).sum()
                + " 行，分片 " + result.chunks() + "，耗时 " + result.costMs() + "ms", output);
    }
}
