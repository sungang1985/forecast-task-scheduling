package com.forecast.gateway.filter;

import com.forecast.common.constant.TraceHeaders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 访问日志 + 请求 ID：为每个请求生成 X-Request-Id 并透传给下游，记录路由、状态码与耗时。
 * 链路 traceparent 由 Micrometer Tracing 自动注入与透传。
 */
@Slf4j
@Component
public class AccessLogGlobalFilter implements GlobalFilter, Ordered {

    private static final String REQUEST_ID = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long begin = System.currentTimeMillis();
        String requestId = exchange.getRequest().getHeaders().getFirst(REQUEST_ID);
        String id = requestId == null ? UUID.randomUUID().toString().replace("-", "") : requestId;
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> h.set(REQUEST_ID, id)))
                .build();
        mutated.getResponse().getHeaders().set(REQUEST_ID, id);
        return chain.filter(mutated).doFinally(signal -> {
            Route route = mutated.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            log.info("{} {} route={} status={} cost={}ms requestId={} trace={}",
                    mutated.getRequest().getMethod(), mutated.getRequest().getURI().getPath(),
                    route == null ? "-" : route.getId(), mutated.getResponse().getStatusCode(),
                    System.currentTimeMillis() - begin, id,
                    mutated.getResponse().getHeaders().getFirst(TraceHeaders.TRACE_ID));
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
