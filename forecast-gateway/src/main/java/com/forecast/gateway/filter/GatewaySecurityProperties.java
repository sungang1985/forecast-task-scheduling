package com.forecast.gateway.filter;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关鉴权配置。
 */
@Data
@ConfigurationProperties(prefix = "gateway.security")
public class GatewaySecurityProperties {

    /** 是否开启 API Key 鉴权 */
    private boolean enabled = false;
    private List<String> apiKeys = new ArrayList<>();
    /** 免鉴权路径 */
    private List<String> excludePaths = new ArrayList<>(List.of("/actuator/health", "/actuator/health/**",
            "/actuator/info", "/actuator/prometheus"));
}
