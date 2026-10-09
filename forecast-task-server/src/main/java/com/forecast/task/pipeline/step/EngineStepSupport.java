package com.forecast.task.pipeline.step;

import com.forecast.task.engine.EngineEndpoint;
import com.forecast.task.engine.EngineEndpointResolver;
import com.forecast.task.pipeline.PipelineContext;
import com.forecast.task.service.TaskRecordService;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 步骤公共能力：获取（并记录）任务绑定的 Python 引擎实例、生成提交幂等号。
 */
@Component
public class EngineStepSupport {

    private final EngineEndpointResolver resolver;
    private final TaskRecordService recordService;

    public EngineStepSupport(EngineEndpointResolver resolver, TaskRecordService recordService) {
        this.resolver = resolver;
        this.recordService = recordService;
    }

    public EngineEndpoint endpoint(PipelineContext ctx) {
        boolean first = ctx.getEngineEndpoint() == null;
        EngineEndpoint endpoint = ctx.engineEndpoint(resolver::resolve);
        if (first) {
            recordService.updateEngineEndpoint(ctx.getTaskId(), endpoint.baseUrl());
        }
        return endpoint;
    }

    /**
     * 提交幂等号：同一次步骤执行内的 HTTP 重试复用同一号（引擎侧去重），步骤级重试生成新号。
     */
    public String requestId(PipelineContext ctx, String step) {
        return ctx.getTaskId() + "-" + step + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
