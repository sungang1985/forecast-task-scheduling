package com.forecast.api.client;

import com.forecast.api.dto.ForecastPointVO;
import com.forecast.api.dto.ForecastTaskRequest;
import com.forecast.api.dto.TaskDetail;
import com.forecast.api.dto.TaskSubmitResult;
import com.forecast.common.model.PageResult;
import com.forecast.common.model.R;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 预测任务服务声明式 HTTP 客户端（Spring Framework 6 HTTP Interface，基于 RestClient，不依赖 Feign）。
 * <p>
 * 通过 {@link ForecastTaskClients#create} 创建实例；在 Spring Cloud 中配合 {@code @LoadBalanced RestClient.Builder}
 * 与服务名（如 {@code http://forecast-task-server}）即可经注册中心负载均衡调用（Bean 配置见 {@link ForecastTaskClients}）。
 * <p>
 * 服务端业务错误（JSON 响应体为统一结构 {@link R} 且 code != 0，含 4xx 与 5xx）不会抛出异常，而是直接返回该 R；
 * 非 R 结构的错误响应抛 {@link ForecastClientException}；网络异常（含读取响应体中断）及注册中心无可用实例抛
 * {@code ResourceAccessException}。业务错误不会被熔断器记为失败，需要时用 {@link ForecastTaskClients#isServerError(R)} 判断。
 * 完整约定见 {@link ForecastTaskClients}。
 */
@HttpExchange("/api/v1")
public interface ForecastTaskClient {

    /**
     * 提交预测任务。
     *
     * @param taskType    任务类型，如 LOAD / PRICE
     * @param mode        preprocess / train / predict / full
     * @param waitSeconds 同步等待秒数，0 表示异步立即返回
     */
    @PostExchange("/forecast/{taskType}/{mode}")
    R<TaskSubmitResult> submit(@PathVariable("taskType") String taskType,
                               @PathVariable("mode") String mode,
                               @RequestParam("waitSeconds") int waitSeconds,
                               @RequestBody ForecastTaskRequest request);

    @GetExchange("/tasks/{taskId}")
    R<TaskDetail> getTask(@PathVariable("taskId") String taskId);

    @PostExchange("/tasks/{taskId}/cancel")
    R<Boolean> cancel(@PathVariable("taskId") String taskId);

    @GetExchange("/tasks/{taskId}/results")
    R<PageResult<ForecastPointVO>> getResults(@PathVariable("taskId") String taskId,
                                              @RequestParam("page") long page,
                                              @RequestParam("size") long size);
}
