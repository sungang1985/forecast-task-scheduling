package com.forecast.api.client;

import com.forecast.api.dto.ForecastPointVO;
import com.forecast.api.dto.ForecastTaskRequest;
import com.forecast.api.dto.TaskDetail;
import com.forecast.api.dto.TaskSubmitResult;
import com.forecast.common.model.PageResult;
import com.forecast.common.model.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 预测任务服务 OpenFeign 客户端。
 * <p>
 * 其他微服务引入 forecast-api 与 spring-cloud-starter-openfeign 后，
 * 通过 {@code @EnableFeignClients(basePackageClasses = ForecastTaskClient.class)} 即可调用。
 * 需要熔断降级时开启 {@code spring.cloud.openfeign.circuitbreaker.enabled=true}，
 * 并将 {@link ForecastTaskClientFallbackFactory} 注册为 Spring Bean。
 */
@FeignClient(name = "${forecast.client.service-id:forecast-task-server}",
        contextId = "forecastTaskClient",
        path = "/api/v1",
        fallbackFactory = ForecastTaskClientFallbackFactory.class)
public interface ForecastTaskClient {

    /**
     * 提交预测任务。
     *
     * @param taskType    任务类型，如 LOAD / PRICE
     * @param mode        preprocess / train / predict / full
     * @param waitSeconds 同步等待秒数，0 表示异步立即返回
     */
    @PostMapping("/forecast/{taskType}/{mode}")
    R<TaskSubmitResult> submit(@PathVariable("taskType") String taskType,
                               @PathVariable("mode") String mode,
                               @RequestParam(value = "waitSeconds", defaultValue = "0") int waitSeconds,
                               @RequestBody ForecastTaskRequest request);

    @GetMapping("/tasks/{taskId}")
    R<TaskDetail> getTask(@PathVariable("taskId") String taskId);

    @PostMapping("/tasks/{taskId}/cancel")
    R<Boolean> cancel(@PathVariable("taskId") String taskId);

    @GetMapping("/tasks/{taskId}/results")
    R<PageResult<ForecastPointVO>> getResults(@PathVariable("taskId") String taskId,
                                              @RequestParam(value = "page", defaultValue = "1") long page,
                                              @RequestParam(value = "size", defaultValue = "500") long size);
}
