package com.forecast.task.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forecast.common.constant.TraceHeaders;
import com.forecast.common.exception.ErrorCode;
import com.forecast.common.model.R;
import com.forecast.task.config.ForecastProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * API Key 鉴权（forecast.security.enabled=true 时生效）。网关已统一鉴权时可关闭；
 * 直连服务时作为兜底防护。比较使用常量时间算法，防止时序攻击。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ForecastProperties properties;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public ApiKeyAuthFilter(ForecastProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        ForecastProperties.Security security = properties.getSecurity();
        if (!security.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return security.getExcludePaths().stream().anyMatch(p -> matcher.match(p, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(TraceHeaders.API_KEY);
        if (key != null && isValid(key)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), R.fail(ErrorCode.UNAUTHORIZED, "缺少或错误的 X-API-Key"));
    }

    private boolean isValid(String key) {
        byte[] provided = key.getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (String expected : properties.getSecurity().getApiKeys()) {
            if (expected == null || expected.isBlank()) {
                continue;
            }
            matched |= MessageDigest.isEqual(provided, expected.getBytes(StandardCharsets.UTF_8));
        }
        return matched;
    }
}
