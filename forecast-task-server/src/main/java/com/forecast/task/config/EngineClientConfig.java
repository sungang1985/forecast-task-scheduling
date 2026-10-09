package com.forecast.task.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.forecast.common.constant.TraceHeaders;
import com.forecast.task.engine.EngineCallException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Python 引擎 HTTP 客户端配置：
 * <ul>
 *     <li>Apache HttpClient5 连接池，复用连接；</li>
 *     <li>snake_case JSON，与 Python 侧约定一致；</li>
 *     <li>透传 traceId / taskId 请求头，引擎日志可与任务关联；可选携带 X-Engine-Token 访问令牌；</li>
 *     <li>基于 Spring Boot 注入的 RestClient.Builder 构建，自动带 Micrometer 观测（http.client.requests 指标与链路）；</li>
 *     <li>Resilience4j 重试（指数退避）+ 熔断。</li>
 * </ul>
 */
@Configuration
public class EngineClientConfig {

    public static final String ENGINE_RESILIENCE_NAME = "pythonEngine";

    /**
     * 引擎专用 ObjectMapper。注意不能注册为 Bean，否则会替换 Spring MVC 默认的 ObjectMapper。
     */
    public static ObjectMapper engineObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Bean(destroyMethod = "close")
    public CloseableHttpClient engineHttpClient(ForecastProperties properties) {
        ForecastProperties.Engine cfg = properties.getEngine();
        PoolingHttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(cfg.getMaxConnections())
                .setMaxConnPerRoute(cfg.getMaxConnectionsPerRoute())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(cfg.getConnectTimeout()))
                        .setSocketTimeout(Timeout.of(cfg.getReadTimeout()))
                        .setValidateAfterInactivity(TimeValue.ofSeconds(10))
                        .build())
                .build();
        return HttpClients.custom()
                .setConnectionManager(cm)
                .evictIdleConnections(TimeValue.ofSeconds(60))
                .evictExpiredConnections()
                // 重试由 Resilience4j 统一控制
                .disableAutomaticRetries()
                .build();
    }

    @Bean
    public RestClient engineRestClient(RestClient.Builder builder, CloseableHttpClient engineHttpClient,
                                       ForecastProperties properties) {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(engineHttpClient);
        factory.setConnectionRequestTimeout(properties.getEngine().getConnectTimeout());
        factory.setReadTimeout(properties.getEngine().getReadTimeout());
        ClientHttpRequestInterceptor traceHeaders = (request, body, execution) -> {
            String traceId = MDC.get(TraceHeaders.MDC_TRACE_ID);
            String taskId = MDC.get(TraceHeaders.MDC_TASK_ID);
            if (traceId != null) {
                request.getHeaders().set(TraceHeaders.TRACE_ID, traceId);
            }
            if (taskId != null) {
                request.getHeaders().set(TraceHeaders.TASK_ID, taskId);
            }
            String token = properties.getEngine().getToken();
            if (token != null && !token.isBlank()) {
                request.getHeaders().set("X-Engine-Token", token);
            }
            return execution.execute(request, body);
        };
        return builder
                .requestFactory(factory)
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                    converters.add(new MappingJackson2HttpMessageConverter(engineObjectMapper()));
                })
                .requestInterceptor(traceHeaders)
                .build();
    }

    @Bean
    public Retry engineRetry(RetryRegistry registry, ForecastProperties properties) {
        ForecastProperties.Engine cfg = properties.getEngine();
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(Math.max(1, cfg.getRetryMaxAttempts()))
                .intervalFunction(IntervalFunction.ofExponentialBackoff(cfg.getRetryInitialInterval(), 2.0))
                .retryOnException(EngineClientConfig::isTransient)
                .build();
        return registry.retry(ENGINE_RESILIENCE_NAME, config);
    }

    @Bean
    public CircuitBreaker engineCircuitBreaker(CircuitBreakerRegistry registry, ForecastProperties properties) {
        ForecastProperties.Engine cfg = properties.getEngine();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(cfg.getCircuitFailureRateThreshold())
                .slidingWindowSize(cfg.getCircuitSlidingWindowSize())
                .minimumNumberOfCalls(Math.min(10, cfg.getCircuitSlidingWindowSize()))
                .waitDurationInOpenState(cfg.getCircuitOpenWait())
                .permittedNumberOfCallsInHalfOpenState(3)
                // 只有网络/5xx 等瞬时故障计入失败率，业务性 4xx 不触发熔断
                .recordException(EngineClientConfig::isTransient)
                .build();
        return registry.circuitBreaker(ENGINE_RESILIENCE_NAME, config);
    }

    private static boolean isTransient(Throwable e) {
        return e instanceof EngineCallException ece && ece.isRetryable();
    }
}
