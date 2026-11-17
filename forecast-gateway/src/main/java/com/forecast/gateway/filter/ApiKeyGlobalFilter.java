package com.forecast.gateway.filter;

import com.forecast.common.constant.TraceHeaders;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 统一 API Key 鉴权（gateway.security.enabled=true 时生效）。
 */
@Component
public class ApiKeyGlobalFilter implements GlobalFilter, Ordered {

    private final GatewaySecurityProperties properties;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public ApiKeyGlobalFilter(GatewaySecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getURI().getPath();
        if (properties.getExcludePaths().stream().anyMatch(p -> matcher.match(p, path))) {
            return chain.filter(exchange);
        }
        String key = exchange.getRequest().getHeaders().getFirst(TraceHeaders.API_KEY);
        if (key != null && valid(key)) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"code\":10401,\"message\":\"缺少或错误的 X-API-Key\"}".getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private boolean valid(String key) {
        byte[] provided = key.getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (String expected : properties.getApiKeys()) {
            if (expected != null && !expected.isBlank()) {
                matched |= MessageDigest.isEqual(provided, expected.getBytes(StandardCharsets.UTF_8));
            }
        }
        return matched;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
