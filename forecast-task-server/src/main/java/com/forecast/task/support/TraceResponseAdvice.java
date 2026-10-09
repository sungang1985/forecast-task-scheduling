package com.forecast.task.support;

import com.forecast.common.constant.TraceHeaders;
import com.forecast.common.model.R;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 为统一响应填充 traceId，并写入响应头 X-Trace-Id。
 */
@RestControllerAdvice
public class TraceResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        String traceId = MDC.get(TraceHeaders.MDC_TRACE_ID);
        if (traceId != null) {
            response.getHeaders().set(TraceHeaders.TRACE_ID, traceId);
            if (body instanceof R<?> r && r.getTraceId() == null) {
                r.setTraceId(traceId);
            }
        }
        return body;
    }
}
