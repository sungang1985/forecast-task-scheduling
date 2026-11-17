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

    private Engine engine = new Engine();
    private Cache cache = new Cache();
    private Demo demo = new Demo();

    @Data
    public static class Engine {
        /** 直连地址（优先）；为空时通过注册中心按 serviceId 负载均衡选择实例 */
        private String baseUrl;
        /** 与 Python 引擎约定的访问令牌（请求头 X-Engine-Token），为空表示不鉴权 */
        private String token;
        private String serviceId = "forecast-python-engine";
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(60);
        private int maxConnections = 200;
        private int maxConnectionsPerRoute = 50;
        /** 轮询 Python 异步任务状态的初始/最大间隔 */
        private Duration pollInitialInterval = Duration.ofMillis(500);
        private Duration pollMaxInterval = Duration.ofSeconds(2);
        /** 任务结束后是否清理 Python 端的临时数据集 */
        private boolean cleanupDatasets = true;
        /** 分页拉取预测结果的页大小 */
        private int resultPageSize = 5000;
        /** 调用重试次数（含首次） */
        private int retryMaxAttempts = 3;
        private Duration retryInitialInterval = Duration.ofMillis(500);
        /** 熔断：失败率阈值与打开后等待时长 */
        private float circuitFailureRateThreshold = 50f;
        private int circuitSlidingWindowSize = 20;
        private Duration circuitOpenWait = Duration.ofSeconds(30);
    }

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
