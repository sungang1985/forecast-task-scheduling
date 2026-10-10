package com.forecast.api.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forecast.common.constant.TraceHeaders;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@link ForecastTaskClient} 工厂。
 * <p>
 * Spring Cloud 微服务中按服务名经注册中心负载均衡调用：
 * <pre>{@code
 * // 保留 Boot 默认的 RestClient.Builder（@Primary），否则声明任何 RestClient.Builder Bean 都会让 Boot 的自动配置失效
 * @Bean
 * @Primary
 * @Scope("prototype")
 * RestClient.Builder restClientBuilder(RestClientBuilderConfigurer configurer) {
 *     return configurer.configure(RestClient.builder());
 * }
 *
 * // 负载均衡专用 builder：同样经 configurer 配置，保留链路追踪、消息转换等 Boot 定制
 * @Bean
 * @LoadBalanced
 * @Scope("prototype")
 * RestClient.Builder loadBalancedRestClientBuilder(RestClientBuilderConfigurer configurer) {
 *     return configurer.configure(RestClient.builder());
 * }
 *
 * @Bean
 * ForecastTaskClient forecastTaskClient(@LoadBalanced RestClient.Builder builder) {
 *     return ForecastTaskClients.create(builder, "http://forecast-task-server", "your-api-key");
 * }
 * }</pre>
 * 不经注册中心（直连服务地址或网关地址）时，传入普通的（非 @LoadBalanced）builder 与实际地址；
 * 不要把直连地址交给 @LoadBalanced builder，它会把主机名当作服务名解析。
 * <p>
 * 返回与异常约定：
 * <ul>
 *     <li>服务端业务错误（统一结构 R，code != 0，如参数错误、任务不存在、未通过鉴权）：<b>不抛异常</b>，返回该 R；</li>
 *     <li>其他错误响应（网关/代理错误页、Spring Boot 默认错误 JSON、空响应体等）：抛 {@link ForecastClientException}，携带 HTTP 状态码；</li>
 *     <li>网络异常（连接失败、超时）：抛 Spring 的 {@code ResourceAccessException}。</li>
 * </ul>
 * 需要熔断/重试时，可在调用处使用 Resilience4j 包装。
 */
public final class ForecastTaskClients {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BODY_SNIPPET = 500;

    private ForecastTaskClients() {
    }

    /**
     * @param builder RestClient.Builder（可带 @LoadBalanced、超时、拦截器等配置）；会被克隆，不影响原实例
     * @param baseUrl 服务地址：{@code http://<服务名>}（配合 @LoadBalanced builder）或直连/网关地址（配合普通 builder）
     * @param apiKey  服务端开启 API Key 鉴权时传入，可为 null
     */
    public static ForecastTaskClient create(RestClient.Builder builder, String baseUrl, String apiKey) {
        RestClient.Builder configured = builder.clone()
                .baseUrl(baseUrl)
                .requestInterceptor(new BusinessErrorInterceptor())
                // 能到达这里的错误响应均已由拦截器确认是服务端统一结构 R：不抛异常，交给调用方按 code 判断
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
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
     * 高级用法：使用已构建好的 RestClient（需自行设置 baseUrl 与错误处理，本类的返回与异常约定不适用）。
     */
    public static ForecastTaskClient create(RestClient restClient) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(ForecastTaskClient.class);
    }

    /**
     * 只有响应体确实是服务端统一结构 R（JSON 对象且 code 为非 0 整数）的错误响应才放行；
     * 放行时缓存响应体，便于后续消息转换器再次读取。
     */
    static final class BusinessErrorInterceptor implements ClientHttpRequestInterceptor {

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            ClientHttpResponse response = execution.execute(request, body);
            HttpStatusCode status = response.getStatusCode();
            if (!status.isError()) {
                return response;
            }
            byte[] content;
            try (InputStream in = response.getBody()) {
                content = StreamUtils.copyToByteArray(in);
            } catch (IOException e) {
                response.close();
                throw new ForecastClientException(status.value(), String.valueOf(request.getURI()), null);
            }
            if (isBusinessError(content)) {
                return new BufferedResponse(response, content);
            }
            response.close();
            String text = new String(content, StandardCharsets.UTF_8);
            throw new ForecastClientException(status.value(), String.valueOf(request.getURI()),
                    text.length() > MAX_BODY_SNIPPET ? text.substring(0, MAX_BODY_SNIPPET) : text);
        }

        static boolean isBusinessError(byte[] content) {
            if (content.length == 0) {
                return false;
            }
            try {
                JsonNode node = MAPPER.readTree(content);
                JsonNode code = node == null ? null : node.get("code");
                return node != null && node.isObject() && code != null && code.canConvertToInt() && code.isIntegralNumber()
                        && code.intValue() != 0;
            } catch (IOException e) {
                return false;
            }
        }
    }

    /**
     * 已缓存响应体的响应包装。
     */
    static final class BufferedResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final byte[] body;

        BufferedResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
