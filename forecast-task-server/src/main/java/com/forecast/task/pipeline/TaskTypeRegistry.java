package com.forecast.task.pipeline;

import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import com.forecast.task.config.ForecastProperties;
import com.forecast.task.config.ForecastProperties.TaskTypeProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * 预测任务类型注册表（来自配置 forecast.task-types，支持配置中心热刷新）。
 */
@Component
public class TaskTypeRegistry {

    private final ForecastProperties properties;

    public TaskTypeRegistry(ForecastProperties properties) {
        this.properties = properties;
    }

    public static String normalize(String taskType) {
        return taskType == null ? null : taskType.trim().toUpperCase(Locale.ROOT);
    }

    public TaskTypeProperties require(String taskType) {
        TaskTypeProperties def = properties.getTaskTypes().get(normalize(taskType));
        if (def == null) {
            throw new BizException(ErrorCode.TASK_TYPE_UNSUPPORTED,
                    "不支持的预测任务类型: " + taskType + "，可选: " + properties.getTaskTypes().keySet());
        }
        return def;
    }

    public Map<String, TaskTypeProperties> all() {
        return properties.getTaskTypes();
    }
}
