package com.forecast.task.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

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
        private boolean enabled = true;
        /** 本地缓存最大行数（按行数加权淘汰） */
        private long localMaxRows = 5_000_000L;
        /** 历史分段（已稳定数据）缓存时长 */
        private Duration historyTtl = Duration.ofHours(12);
        /** 近期分段（可能仍在补录的数据）缓存时长 */
        private Duration hotTtl = Duration.ofMinutes(5);
        /** 距当前时间多久以内的分段视为“近期分段” */
        private Duration hotWindow = Duration.ofDays(3);
        /** 是否启用 Redis 二级缓存（多实例共享） */
        private boolean redisEnabled = false;
        private String redisKeyPrefix = "forecast:data:";
        /** 多实例下通过 Redis 发布订阅同步本地缓存失效 */
        private String evictChannel = "forecast:cache:evict";
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
