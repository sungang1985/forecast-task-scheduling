package com.forecast.task.pipeline;

import com.forecast.common.enums.ForecastMode;
import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import com.forecast.task.config.ForecastProperties;
import com.forecast.task.config.ForecastProperties.TaskTypeProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 流程注册表：根据 (任务类型, 执行模式) 解析出有序步骤列表。
 * 步骤实现来自 Spring 容器中的全部 {@link ForecastStep} Bean，编排来自配置 forecast.pipelines。
 */
@Slf4j
@Component
public class PipelineRegistry {

    private final ForecastProperties properties;
    private final Map<String, ForecastStep> steps = new LinkedHashMap<>();

    public PipelineRegistry(ForecastProperties properties, List<ForecastStep> stepBeans) {
        this.properties = properties;
        for (ForecastStep step : stepBeans) {
            ForecastStep previous = steps.put(step.name(), step);
            if (previous != null) {
                throw new IllegalStateException("流程步骤名重复: " + step.name());
            }
        }
    }

    /**
     * 启动时校验所有流程配置引用的步骤均存在，配置错误尽早暴露。
     */
    @PostConstruct
    public void validate() {
        properties.getPipelines().forEach((pipeline, modes) -> modes.forEach((mode, names) -> {
            ForecastMode.parse(mode);
            names.forEach(name -> {
                if (!steps.containsKey(name)) {
                    throw new IllegalStateException("流程 " + pipeline + "." + mode + " 引用了不存在的步骤: " + name
                            + "，已注册步骤: " + steps.keySet());
                }
            });
        }));
        properties.getTaskTypes().forEach((type, def) -> {
            if (!properties.getPipelines().containsKey(def.getPipeline())) {
                throw new IllegalStateException("任务类型 " + type + " 引用了不存在的流程: " + def.getPipeline());
            }
        });
        log.info("已注册流程步骤: {}，流程: {}", steps.keySet(), properties.getPipelines().keySet());
    }

    public List<ForecastStep> resolve(TaskTypeProperties typeDef, ForecastMode mode) {
        Map<String, List<String>> pipeline = properties.getPipelines().get(typeDef.getPipeline());
        if (pipeline == null) {
            throw new BizException(ErrorCode.PIPELINE_NOT_FOUND, "流程不存在: " + typeDef.getPipeline());
        }
        List<String> names = null;
        for (Map.Entry<String, List<String>> e : pipeline.entrySet()) {
            if (e.getKey().toUpperCase(Locale.ROOT).equals(mode.name())) {
                names = e.getValue();
            }
        }
        if (names == null || names.isEmpty()) {
            throw new BizException(ErrorCode.PIPELINE_NOT_FOUND,
                    "流程 " + typeDef.getPipeline() + " 未定义模式 " + mode);
        }
        List<ForecastStep> resolved = new ArrayList<>(names.size());
        for (String name : names) {
            ForecastStep step = steps.get(name);
            if (step == null) {
                throw new BizException(ErrorCode.PIPELINE_NOT_FOUND, "流程步骤不存在: " + name);
            }
            resolved.add(step);
        }
        return resolved;
    }

    public Map<String, String> stepDescriptions() {
        Map<String, String> result = new LinkedHashMap<>();
        steps.forEach((name, step) -> result.put(name, step.description()));
        return result;
    }
}
