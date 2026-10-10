package com.forecast.api.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forecast.common.constant.TraceHeaders;
import com.forecast.common.exception.ErrorCode;
import com.forecast.common.model.R;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
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
 *     <li>服务端业务错误（JSON 响应体为统一结构 R，code != 0）：<b>不抛异常</b>，返回该 R。包括 4xx（参数错误、任务不存在、
 *     未通过鉴权、队列已满）与 5xx（系统内部错误、引擎不可用、任务超时）；即使传入的 builder 自带错误状态处理器也是如此；</li>
 *     <li>其他错误响应（网关/代理错误页、Spring Boot 默认错误 JSON、空响应体、非 JSON 响应体等）：
 *     抛 {@link ForecastClientException}（携带 HTTP 状态码）；若传入的 builder 已配置错误状态处理器，则由该处理器优先处理，
 *     该处理器<b>必须抛出异常</b>：处理器不抛异常时 RestClient 会继续把响应体按 R 解码，非 R 错误可能得到 code == 0 的 R 或 null；</li>
 *     <li>网络异常（连接失败、超时、读取响应体中断）以及按服务名调用但注册中心无可用实例：抛 Spring 的 {@code ResourceAccessException}。</li>
 * </ul>
 * 业务错误在客户端被视为一次正常返回：http.client.requests 指标、链路追踪、负载均衡统计中都记为 200，
 * 包在调用处的熔断器/重试也不会将其记为失败。服务端故障引起的业务错误（如 5xx）需要计入熔断时，用 {@link #isServerError(R)} 判断，
 * 例如 Resilience4j {@code CircuitBreakerConfig.custom().recordResult(r -> r instanceof R<?> res && ForecastTaskClients.isServerError(res))}。
 * 原始 HTTP 状态码放在内部响应头 {@link #ORIGINAL_STATUS_HEADER}，仅拦截器与观测约定可见，接口方法的返回值不含该信息。
 */
public final class ForecastTaskClients {

    /** 业务错误响应改写为 200 后，原始 HTTP 状态码放在该响应头中（仅拦截器、观测约定可见），便于排查 */
    public static final String ORIGINAL_STATUS_HEADER = "X-Forecast-Original-Status";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BODY_SNIPPET = 500;
    /** Spring Cloud LoadBalancer 无可用实例时的异常消息前缀：BlockingLoadBalancerClient 抛 IllegalStateException */
    private static final String NO_INSTANCES_PREFIX = "No instances available";
    /** 引入 spring-retry 后由 RetryLoadBalancerInterceptor 选不到实例时，BlockingLoadBalancerClient 抛 IllegalArgumentException */
    private static final String NULL_INSTANCE_PREFIX = "Service Instance cannot be null";

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
                .requestInterceptor(new BufferingInterceptor())
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

    /**
     * 业务错误是否由服务端故障引起：错误码对应 HTTP 5xx（如系统内部错误、Python 引擎不可用、任务执行超时），
     * 或错误码未定义（如更新版本服务端新增的）。成功或 4xx 类错误（参数错误、任务不存在、限流等）返回 false。
     * <p>
     * 业务错误不抛异常，熔断器/重试默认不会把它记为失败；需要时以此作为 Resilience4j 的 recordResult / retryOnResult 条件。
     */
    public static boolean isServerError(R<?> result) {
        if (result == null || result.isSuccess()) {
            return false;
        }
        return ErrorCode.of(result.getCode()).map(code -> code.getHttpStatus() >= 500).orElse(true);
    }

    static boolean isBusinessError(HttpHeaders headers, byte[] content) {
        if (content.length == 0 || !isJson(headers)) {
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
     * 只有 JSON 响应体（application/json 或 application/*+json）才可能按 R 解码；
     * 其他类型（text/plain、缺失 Content-Type 等）即使内容像 R，改写为 200 后也会因无法解码而抛出非约定的异常。
     */
    private static boolean isJson(HttpHeaders headers) {
        MediaType contentType;
        try {
            contentType = headers.getContentType();
        } catch (InvalidMediaTypeException e) {
            return false;
        }
        return contentType != null && "application".equals(contentType.getType())
                && ("json".equals(contentType.getSubtype()) || "json".equals(contentType.getSubtypeSuffix()));
    }

    /**
     * 在拦截器内读完并缓存响应体：
     * <ul>
     *     <li>读取中断（含成功响应）以 IOException 抛出，由 RestClient 统一包装为 ResourceAccessException；</li>
     *     <li>服务端统一结构 R 的业务错误改写为 200（原状态码放入 {@link #ORIGINAL_STATUS_HEADER}），
     *     使其不受任何错误状态处理器影响、按 R 正常解码；其他错误响应保留原状态码，交给状态处理器。</li>
     * </ul>
     * 接口返回的响应体均有上限（结果分页最多 1000 点），缓存的额外内存开销可忽略。
     */
    static final class BufferingInterceptor implements ClientHttpRequestInterceptor {

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            ClientHttpResponse response = execution.execute(request, body);
            byte[] content;
            try (InputStream in = response.getBody()) {
                content = StreamUtils.copyToByteArray(in);
            } catch (IOException e) {
                // 读取响应体中断属于网络异常：关闭后原样抛出，由 RestClient 包装为 ResourceAccessException
                response.close();
                throw e;
            }
            HttpStatusCode status = response.getStatusCode();
            if (status.isError() && isBusinessError(response.getHeaders(), content)) {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(response.getHeaders());
                headers.set(ORIGINAL_STATUS_HEADER, String.valueOf(status.value()));
                return new BufferedResponse(response, HttpStatus.OK, headers, content);
            }
            return new BufferedResponse(response, status, response.getHeaders(), content);
        }
    }

    /**
     * Spring Cloud LoadBalancer 无可用实例时抛 IllegalStateException（引入 spring-retry 后为 IllegalArgumentException），
     * RestClient 不会包装它们；转为 IOException 后由 RestClient 统一包装为 ResourceAccessException，与其他“服务不可达”情形一致。
     */
    static final class NoInstanceInterceptor implements ClientHttpRequestInterceptor {

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            try {
                return execution.execute(request, body);
            } catch (IllegalStateException | IllegalArgumentException e) {
                String message = e.getMessage();
                if (message != null && (e instanceof IllegalStateException && message.startsWith(NO_INSTANCES_PREFIX)
                        || e instanceof IllegalArgumentException && message.startsWith(NULL_INSTANCE_PREFIX))) {
                    throw new IOException("注册中心中没有可用的预测任务服务实例: " + message, e);
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
