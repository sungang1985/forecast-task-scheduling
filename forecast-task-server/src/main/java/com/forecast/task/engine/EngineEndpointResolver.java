package com.forecast.task.engine;

import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import com.forecast.task.config.ForecastProperties;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 解析 Python 引擎地址：
 * <ol>
 *     <li>配置了 forecast.engine.base-url 时直连（K8s Service、SLB 等场景）；</li>
 *     <li>否则从注册中心按 serviceId 发现实例并轮询选择。使用阻塞式 CompositeDiscoveryClient，
 *     按 order 依次查询各注册中心，取第一个有实例的结果（Nacos 优先，simple discovery 仅作兜底），
 *     避免多个来源的实例被合并后轮询到兜底地址。Nacos 只返回健康实例。</li>
 * </ol>
 */
@Component
public class EngineEndpointResolver {

    private final ForecastProperties properties;
    private final DiscoveryClient discoveryClient;
    private final AtomicInteger roundRobin = new AtomicInteger();

    public EngineEndpointResolver(ForecastProperties properties, DiscoveryClient discoveryClient) {
        this.properties = properties;
        this.discoveryClient = discoveryClient;
    }

    public EngineEndpoint resolve() {
        ForecastProperties.Engine cfg = properties.getEngine();
        if (StringUtils.hasText(cfg.getBaseUrl())) {
            return new EngineEndpoint("static", cfg.getBaseUrl());
        }
        List<ServiceInstance> instances = discoveryClient.getInstances(cfg.getServiceId());
        if (instances == null || instances.isEmpty()) {
            throw new BizException(ErrorCode.ENGINE_UNAVAILABLE, "注册中心中没有可用的 Python 引擎实例: " + cfg.getServiceId());
        }
        ServiceInstance instance = instances.get(Math.floorMod(roundRobin.getAndIncrement(), instances.size()));
        return new EngineEndpoint(instance.getInstanceId(), instance.getUri().toString());
    }

    /**
     * 按已记录的地址重建（如模型绑定的实例）。
     */
    public EngineEndpoint of(String baseUrl) {
        return new EngineEndpoint("pinned", baseUrl);
    }
}
