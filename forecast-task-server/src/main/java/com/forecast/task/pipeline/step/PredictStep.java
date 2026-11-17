package com.forecast.task.pipeline.step;

import com.forecast.api.dto.ForecastTaskRequest;
import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import com.forecast.task.engine.EngineEndpoint;
import com.forecast.task.engine.EngineTaskAwaiter;
import com.forecast.task.engine.PythonEngineClient;
import com.forecast.task.engine.dto.EngineDtos.EngineTaskRef;
import com.forecast.task.engine.dto.EngineDtos.EngineTaskStatus;
import com.forecast.task.engine.dto.EngineDtos.PredictRequest;
import com.forecast.task.pipeline.ContextKeys;
import com.forecast.task.pipeline.ForecastStep;
import com.forecast.task.pipeline.PipelineContext;
import com.forecast.task.pipeline.StepResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结果预测：使用训练产出（或指定/最新可用）模型，结合近期历史与外生变量预测目标时段。
 */
@Component
public class PredictStep implements ForecastStep {

    public static final String NAME = "PREDICT";

    private final PythonEngineClient client;
    private final EngineTaskAwaiter awaiter;
    private final EngineStepSupport support;

    public PredictStep(PythonEngineClient client, EngineTaskAwaiter awaiter, EngineStepSupport support) {
        this.client = client;
        this.awaiter = awaiter;
        this.support = support;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "模型预测";
    }

    @Override
    public StepResult execute(PipelineContext ctx) {
        ForecastTaskRequest req = ctx.getRequest();
        String modelId = ctx.get(ContextKeys.MODEL_ID);
        if (modelId == null) {
            throw new BizException(ErrorCode.MODEL_NOT_FOUND, "预测缺少可用模型");
        }
        EngineEndpoint endpoint = support.endpoint(ctx);
        PredictRequest request = new PredictRequest(support.requestId(ctx, NAME), ctx.getTaskId(), ctx.getTaskType(),
                modelId, ctx.get(ContextKeys.DATASET_ID), req.getForecastStart(), req.getForecastEnd(), req.getParams());
        EngineTaskRef ref = client.submitPredict(endpoint, request);
        EngineTaskStatus status = awaiter.await(ctx, endpoint, ref.taskId());

        Map<String, Object> result = status.result() == null ? Map.of() : status.result();
        String predictionId = (String) result.get("prediction_id");
        ctx.put(ContextKeys.PREDICTION_ID, predictionId);
        ctx.summary("modelId", modelId);
        ctx.summary("forecastWindow", req.getForecastStart() + " ~ " + req.getForecastEnd());

        Map<String, Object> output = new LinkedHashMap<>(result);
        output.put("modelId", modelId);
        return StepResult.of("预测完成，结果 " + result.get("rows") + " 条", output, ref.taskId());
    }
}
