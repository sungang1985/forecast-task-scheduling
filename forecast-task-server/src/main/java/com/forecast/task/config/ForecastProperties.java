package com.forecast.task.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private Executor executor = new Executor();
    private Engine engine = new Engine();
    private Transfer transfer = new Transfer();
    private Cache cache = new Cache();
    private Scheduler scheduler = new Scheduler();
    private Task task = new Task();
    private Security security = new Security();
    private Callback callback = new Callback();
    private Demo demo = new Demo();

    /** 预测任务类型定义，key 为任务类型编码（如 LOAD、PRICE） */
    private Map<String, TaskTypeProperties> taskTypes = new LinkedHashMap<>();

    /**
     * 流程定义：流程名 -> (执行模式 -> 有序步骤名列表)。
     * 新增预测流程时只需新增 ForecastStep 实现并在此编排，无需改动调度/执行代码。
     */
    private Map<String, Map<String, List<String>>> pipelines = new LinkedHashMap<>();

    @Data
    public static class Executor {
        /** 预测任务执行线程池 */
        private int corePoolSize = 8;
        private int maxPoolSize = 16;
        private int queueCapacity = 200;
        private int keepAliveSeconds = 60;
        /** 停机时等待运行中任务完成的最长时间 */
        private int awaitTerminationSeconds = 60;
        /** 数据分片并行上传线程池 */
        private int transferPoolSize = 8;
    }

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
    public static class Transfer {
        /** 单个分片最大行数 */
        private int chunkRows = 20000;
        /** 并行上传的分片数 */
        private int parallelism = 2;
        /** 是否 gzip 压缩 */
        private boolean gzip = true;
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
    public static class Scheduler {
        private boolean enabled = true;
        private int poolSize = 4;
        /** 从数据库刷新定时任务配置的间隔 */
        private Duration refreshInterval = Duration.ofSeconds(30);
        /** 错过触发的宽限期，超过且策略为 FIRE_ONCE 时补偿触发一次 */
        private Duration misfireThreshold = Duration.ofMinutes(1);
    }

    @Data
    public static class Task {
        private Duration defaultTimeout = Duration.ofHours(2);
        /** 同步等待最长时间（秒） */
        private int maxWaitSeconds = 300;
        /** 心跳间隔，用于识别宕机节点上的“僵尸任务” */
        private Duration heartbeatInterval = Duration.ofSeconds(30);
        private Duration staleTimeout = Duration.ofMinutes(5);
        /** 可重试步骤的最大执行次数（含首次） */
        private int stepMaxAttempts = 2;
        private Duration stepRetryBackoff = Duration.ofSeconds(3);
        /** 取消检测间隔 */
        private Duration cancelCheckInterval = Duration.ofSeconds(3);
    }

    @Data
    public static class Security {
        /** 是否开启 API Key 鉴权 */
        private boolean enabled = false;
        private List<String> apiKeys = new ArrayList<>();
        /** 免鉴权路径：仅开放探活与指标抓取；loggers/threaddump 等运维端点同样需要鉴权 */
        private List<String> excludePaths = new ArrayList<>(List.of(
                "/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error"));
    }

    @Data
    public static class Callback {
        /** 允许回调的主机白名单（防 SSRF），为空表示不允许任何回调 */
        private List<String> allowedHosts = new ArrayList<>();
        private int maxAttempts = 3;
        private Duration timeout = Duration.ofSeconds(5);
    }

    @Data
    public static class Demo {
        /** 启动时生成演示数据（仅开发环境） */
        private boolean enabled = false;
        private int historyDays = 120;
    }

    @Data
    public static class TaskTypeProperties {
        private String displayName;
        /** 主数据源（预测目标所在表），对应 forecast_data_source.source_code */
        private String primarySource;
        /** 外生变量数据源（气象、负荷等） */
        private List<ExogenousSource> exogenousSources = new ArrayList<>();
        /** 预测目标字段 */
        private List<String> targetFields = new ArrayList<>();
        /** 数据频率（pandas 频率字符串） */
        private String frequency = "15min";
        private String defaultAlgorithm = "gbrt";
        private int defaultHistoryDays = 90;
        /** 预测模式下需要回看的历史天数（用于滞后特征） */
        private int predictLookbackDays = 14;
        private int defaultHorizonHours = 24;
        private Duration timeout;
        /** 引用的流程名 */
        private String pipeline = "standard";
        /** 默认算法参数 */
        private Map<String, Object> params = new LinkedHashMap<>();
    }

    @Data
    public static class ExogenousSource {
        private String source;
        /** true：与主数据按 (series_key, ts) 关联；false：仅按 ts 关联（多 key 时取均值） */
        private boolean joinOnKey = true;
        /** 预测阶段是否需要覆盖预测时段（如气象预报） */
        private boolean coverForecastWindow = true;
    }
}
