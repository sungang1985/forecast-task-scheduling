package com.forecast.task.monitor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.forecast.api.dto.TaskDetail;
import com.forecast.task.config.ExecutorConfig;
import com.forecast.task.config.ForecastProperties;
import com.forecast.task.domain.entity.TaskInstance;
import com.forecast.task.mapper.TaskInstanceMapper;
import com.forecast.task.service.TaskQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 任务结束回调：向请求中的 callbackUrl POST 任务详情（白名单校验在提交时完成），失败按退避重试。
 */
@Slf4j
@Component
public class CallbackNotifier {

    private final TaskQueryService queryService;
    private final TaskInstanceMapper instanceMapper;
    private final ThreadPoolTaskExecutor callbackExecutor;
    private final ForecastProperties properties;
    private final RestClient restClient;

    public CallbackNotifier(TaskQueryService queryService, TaskInstanceMapper instanceMapper,
                            @Qualifier(ExecutorConfig.CALLBACK_EXECUTOR) ThreadPoolTaskExecutor callbackExecutor,
                            ForecastProperties properties, RestClient.Builder builder) {
        this.queryService = queryService;
        this.instanceMapper = instanceMapper;
        this.callbackExecutor = callbackExecutor;
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getCallback().getTimeout());
        factory.setReadTimeout(properties.getCallback().getTimeout());
        this.restClient = builder.requestFactory(factory).build();
    }

    @EventListener
    public void onTaskEvent(TaskEvent event) {
        if (!event.isFinished()) {
            return;
        }
        callbackExecutor.execute(() -> sendCallback(event.taskId()));
    }

    private void sendCallback(String taskId) {
        TaskInstance instance = instanceMapper.selectOne(Wrappers.<TaskInstance>lambdaQuery()
                .select(TaskInstance::getCallbackUrl).eq(TaskInstance::getTaskId, taskId));
        if (instance == null || !StringUtils.hasText(instance.getCallbackUrl())) {
            return;
        }
        TaskDetail detail = queryService.detail(taskId);
        int maxAttempts = Math.max(1, properties.getCallback().getMaxAttempts());
        String status = "FAILED";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                restClient.post().uri(instance.getCallbackUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(detail)
                        .retrieve().toBodilessEntity();
                status = "SUCCESS";
                break;
            } catch (RuntimeException e) {
                log.warn("任务回调失败 taskId={} attempt={}/{}: {}", taskId, attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        instanceMapper.update(null, Wrappers.<TaskInstance>lambdaUpdate()
                .set(TaskInstance::getCallbackStatus, status)
                .eq(TaskInstance::getTaskId, taskId));
    }
}
