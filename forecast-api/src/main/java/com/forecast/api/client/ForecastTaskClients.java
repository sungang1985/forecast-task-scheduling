package com.forecast.api.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forecast.common.constant.TraceHeaders;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
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
 *     <li>服务端业务错误（统一结构 R，code != 0，如参数错误、任务不存在、未通过鉴权）：<b>不抛异常</b>，返回该 R。
 *     即使传入的 builder 自带错误状态处理器也是如此（客户端 http.client.requests 指标中这类响应记为 200，真实状态见服务端指标）；</li>
 *     <li>其他错误响应（网关/代理错误页、Spring Boot 默认错误 JSON、空响应体等）：抛 {@link ForecastClientException}（携带 HTTP 状态码）；
 *     若传入的 builder 已配置错误状态处理器，则由该处理器优先处理；</li>
 *     <li>网络异常（连接失败、超时、读取响应中断）以及按服务名调用但注册中心无可用实例：抛 Spring 的 {@code ResourceAccessException}。</li>
 * </ul>
 * 需要熔断/重试时，可在调用处使用 Resilience4j 包装。
 */
public final class ForecastTaskClients {

    /** 业务错误响应改写为 200 后，原始 HTTP 状态码放在该响应头中，便于排查 */
    public static final String ORIGINAL_STATUS_HEADER = "X-Forecast-Original-Status";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BODY_SNIPPET = 500;
    /** Spring Cloud LoadBalancer 在无可用实例时抛出的 IllegalStateException 消息前缀 */
    private static final String NO_INSTANCES_PREFIX = "No instances available";

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
                // 放在拦截器链首位（负载均衡拦截器之前），把“注册中心无可用实例”统一为 ResourceAccessException
                .requestInterceptors(interceptors -> interceptors.add(0, new NoInstanceInterceptor()))
                .requestInterceptor(new BusinessErrorInterceptor())
                // 能到达这里的错误响应均不是服务端统一结构 R（业务错误已被拦截器转换），一律视为调用失败
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    String body;
                    try (InputStream in = response.getBody()) {
                        body = new String(StreamUtils.copyToByteArray(in), StandardCharsets.UTF_8);
                    }
                    throw new ForecastClientException(response.getStatusCode().value(), String.valueOf(request.getURI()),
                            body.length() > MAX_BODY_SNIPPET ? body.substring(0, MAX_BODY_SNIPPET) : body);
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

    static boolean isBusinessError(byte[] content) {
        if (content.length == 0) {
            return false;
        }
        try {
            JsonNode node = MAPPER.readTree(content);
            if (node == null || !node.isObject()) {
                return false;
            }
            JsonNode code = node.get("code");
            return code != null && code.isIntegralNumber() && code.canConvertToInt() && code.intValue() != 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 缓存错误响应的响应体：服务端统一结构 R 的业务错误改写为 200（原状态码放入 {@link #ORIGINAL_STATUS_HEADER}），
     * 使其不受任何错误状态处理器影响、按 R 正常解码；其他错误响应保留原状态码，交给状态处理器。
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
                // 读取响应体中断属于网络异常：关闭后原样抛出，由 RestClient 包装为 ResourceAccessException
                response.close();
                throw e;
            }
            if (isBusinessError(content)) {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(response.getHeaders());
                headers.set(ORIGINAL_STATUS_HEADER, String.valueOf(status.value()));
                return new BufferedResponse(response, HttpStatus.OK, headers, content);
            }
            return new BufferedResponse(response, status, response.getHeaders(), content);
        }
    }

    /**
     * Spring Cloud LoadBalancer 无可用实例时抛 IllegalStateException，RestClient 不会包装它；
     * 转为 IOException 后由 RestClient 统一包装为 ResourceAccessException，与其他“服务不可达”情形一致。
     */
    static final class NoInstanceInterceptor implements ClientHttpRequestInterceptor {

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            try {
                return execution.execute(request, body);
            } catch (IllegalStateException e) {
                if (e.getMessage() != null && e.getMessage().startsWith(NO_INSTANCES_PREFIX)) {
                    throw new IOException("注册中心中没有可用的预测任务服务实例: " + e.getMessage(), e);
                }
                throw e;
            }
        }
    }

    /**
     * 已缓存响应体的响应包装。
     */
    static final class BufferedResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final HttpStatusCode status;
        private final HttpHeaders headers;
        private final byte[] body;

        BufferedResponse(ClientHttpResponse delegate, HttpStatusCode status, HttpHeaders headers, byte[] body) {
            this.delegate = delegate;
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        @Override
        public HttpStatusCode getStatusCode() {
            return status;
        }

        @Override
        public String getStatusText() {
            return status instanceof HttpStatus known ? known.getReasonPhrase() : "";
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
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
