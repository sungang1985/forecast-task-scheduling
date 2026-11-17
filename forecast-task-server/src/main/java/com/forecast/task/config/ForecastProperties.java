package com.forecast.task.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


/**
 * 平台全部可配置项（前缀 forecast）。接入 Nacos 配置中心后，修改即可热刷新
 * （Spring Cloud 会在配置变更时重新绑定 @ConfigurationProperties）。
 * 显式指定 Bean 名称，便于在 @Scheduled 等 SpEL 表达式中引用。
 */
@Data
@Component("forecastProperties")
@ConfigurationProperties(prefix = "forecast")
public class ForecastProperties {

    /** 当前节点标识，为空时自动使用 主机名:端口 */
    private String nodeId;
}
