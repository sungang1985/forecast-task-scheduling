package com.forecast.api.client;

import com.forecast.common.constant.TraceHeaders;
import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * {@link ForecastTaskClient} 工厂。
 * <p>
 * Spring Cloud 微服务中的典型用法（经 Nacos 等注册中心负载均衡）：
 * <pre>{@code
 * @Bean
 * @LoadBalanced
 * RestClient.Builder loadBalancedRestClientBuilder() {
 *     return RestClient.builder();
 * }
 *
 * @Bean
 * ForecastTaskClient forecastTaskClient(@LoadBalanced RestClient.Builder builder) {
 *     return ForecastTaskClients.create(builder, "http://forecast-task-server", "your-api-key");
 * }
 * }</pre>
 * 需要熔断/重试时，可在调用处使用 Resilience4j 包装。
 */
public final class ForecastTaskClients {

    private ForecastTaskClients() {
    }

    /**
     * @param builder Spring 注入的 RestClient.Builder（可带 @LoadBalanced、超时、拦截器等配置）；会被克隆，不影响原实例
     * @param baseUrl 服务地址：直连地址或 {@code http://<服务名>}
     * @param apiKey  服务端开启 API Key 鉴权时传入，可为 null
     */
    public static ForecastTaskClient create(RestClient.Builder builder, String baseUrl, String apiKey) {
        RestClient.Builder configured = builder.clone()
                .baseUrl(baseUrl)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    // 服务端业务错误以统一 JSON 结构 R 返回：不抛异常，交给调用方按 code 判断
                    MediaType type = response.getHeaders().getContentType();
                    if (type != null && type.isCompatibleWith(MediaType.APPLICATION_JSON)) {
                        return;
                    }
                    throw new BizException(ErrorCode.INTERNAL_ERROR,
                            "预测任务服务调用失败: HTTP " + response.getStatusCode().value() + " " + request.getURI());
                });
        if (apiKey != null && !apiKey.isBlank()) {
            configured.defaultHeader(TraceHeaders.API_KEY, apiKey);
        }
        return create(configured.build());
    }

    public static ForecastTaskClient create(RestClient.Builder builder, String baseUrl) {
        return create(builder, baseUrl, null);
    }

    /**
     * 使用已构建好的 RestClient（需自行设置 baseUrl 与错误处理）。
     */
    public static ForecastTaskClient create(RestClient restClient) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(ForecastTaskClient.class);
    }
}
