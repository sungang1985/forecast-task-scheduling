package com.forecast.task.pipeline.step;

import com.forecast.api.dto.ForecastTaskRequest;
import com.forecast.task.domain.entity.ForecastModel;
import com.forecast.task.engine.EngineEndpoint;
import com.forecast.task.engine.EngineTaskAwaiter;
import com.forecast.task.engine.PythonEngineClient;
import com.forecast.task.engine.dto.EngineDtos.EngineTaskRef;
import com.forecast.task.engine.dto.EngineDtos.EngineTaskStatus;
import com.forecast.task.engine.dto.EngineDtos.TrainRequest;
import com.forecast.task.pipeline.ContextKeys;
import com.forecast.task.pipeline.ForecastStep;
import com.forecast.task.pipeline.PipelineContext;
import com.forecast.task.pipeline.StepResult;
import com.forecast.task.service.ModelService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型训练：调用 Python 引擎训练，训练产出登记到模型注册表并设为当前可用版本。
 */
@Component
public class TrainStep implements ForecastStep {

    public static final String NAME = "TRAIN";

    private final PythonEngineClient client;
    private final EngineTaskAwaiter awaiter;
    private final EngineStepSupport support;
    private final ModelService modelService;

    public TrainStep(PythonEngineClient client, EngineTaskAwaiter awaiter, EngineStepSupport support,
                     ModelService modelService) {
        this.client = client;
        this.awaiter = awaiter;
        this.support = support;
        this.modelService = modelService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "模型训练并登记模型版本";
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(PipelineContext ctx) {
        ForecastTaskRequest req = ctx.getRequest();
        EngineEndpoint endpoint = support.endpoint(ctx);
        TrainRequest request = new TrainRequest(support.requestId(ctx, NAME), ctx.getTaskId(), ctx.getTaskType(),
                ctx.get(ContextKeys.PROCESSED_DATASET_ID), req.getTargetFields(), req.getAlgorithm(), req.getParams());
        EngineTaskRef ref = client.submitTrain(endpoint, request);
        EngineTaskStatus status = awaiter.await(ctx, endpoint, ref.taskId());

        Map<String, Object> result = status.result() == null ? Map.of() : status.result();
        String modelId = (String) result.get("model_id");
        Map<String, Object> metrics = (Map<String, Object>) result.getOrDefault("metrics", Map.of());
        Number trainRows = (Number) result.get("train_rows");
        ForecastModel model = modelService.register(ctx.getTaskType(), modelId, req.getAlgorithm(), req.getSeriesKeys(),
                req.getTargetFields(), req.getHistoryStart(), req.getHistoryEnd(),
                trainRows == null ? null : trainRows.longValue(), metrics, req.getParams(), ctx.getTaskId(),
                endpoint.baseUrl());
        ctx.put(ContextKeys.MODEL_ID, modelId);
        ctx.summary("modelId", modelId);
        ctx.summary("metrics", metrics.get("overall"));

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("modelId", model.getModelId());
        output.put("algorithm", req.getAlgorithm());
        output.put("trainRows", trainRows);
        output.put("metrics", metrics);
        return StepResult.of("训练完成，模型 " + modelId + "，整体指标 " + metrics.get("overall"), output, ref.taskId());
    }
}
