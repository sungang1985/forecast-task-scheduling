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

    private Cache cache = new Cache();
    private Demo demo = new Demo();

    @Data
    public static class Cache {
        /** 单次 IN 查询的最大 key 数量 */
        private int maxKeysPerQuery = 200;
        /** 读库 fetchSize */
        private int fetchSize = 5000;
    }

    @Data
    public static class Demo {
        /** 启动时生成演示数据（仅开发环境） */
        private boolean enabled = false;
        private int historyDays = 120;
    }
}
