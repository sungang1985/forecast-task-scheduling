package com.forecast.task.pipeline.step;

import com.forecast.task.engine.EngineEndpoint;
import com.forecast.task.engine.EngineTaskAwaiter;
import com.forecast.task.engine.PythonEngineClient;
import com.forecast.task.engine.dto.EngineDtos.EngineTaskRef;
import com.forecast.task.engine.dto.EngineDtos.EngineTaskStatus;
import com.forecast.task.engine.dto.EngineDtos.PreprocessRequest;
import com.forecast.task.pipeline.ContextKeys;
import com.forecast.task.pipeline.ForecastStep;
import com.forecast.task.pipeline.PipelineContext;
import com.forecast.task.pipeline.StepResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据预处理：缺失值填补、异常值处理、多源数据对齐、特征工程（由 Python 引擎执行）。
 */
@Component
public class PreprocessStep implements ForecastStep {

    public static final String NAME = "PREPROCESS";

    private final PythonEngineClient client;
    private final EngineTaskAwaiter awaiter;
    private final EngineStepSupport support;

    public PreprocessStep(PythonEngineClient client, EngineTaskAwaiter awaiter, EngineStepSupport support) {
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
        return "数据预处理（清洗、对齐、特征工程）";
    }

    @Override
    public boolean shouldSkip(PipelineContext ctx) {
        return ctx.has(ContextKeys.PROCESSED_DATASET_ID);
    }

    @Override
    public StepResult execute(PipelineContext ctx) {
        EngineEndpoint endpoint = support.endpoint(ctx);
        PreprocessRequest request = new PreprocessRequest(support.requestId(ctx, NAME), ctx.getTaskId(),
                ctx.getTaskType(), ctx.get(ContextKeys.DATASET_ID), ctx.getRequest().getTargetFields(),
                ctx.getTaskTypeDef().getFrequency(), ctx.getRequest().getParams());
        EngineTaskRef ref = client.submitPreprocess(endpoint, request);
        EngineTaskStatus status = awaiter.await(ctx, endpoint, ref.taskId());

        Map<String, Object> result = status.result() == null ? Map.of() : status.result();
        String processedId = (String) result.get("processed_dataset_id");
        ctx.put(ContextKeys.PROCESSED_DATASET_ID, processedId);
        ctx.summary("processedDatasetId", processedId);
        ctx.summary("preprocessStats", result.get("stats"));
        Map<String, Object> output = new LinkedHashMap<>(result);
        return StepResult.of("预处理完成，数据集 " + processedId, output, ref.taskId());
    }
}
