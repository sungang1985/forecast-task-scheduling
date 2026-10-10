# 量价 / 负荷预测任务调度平台（forecast-task-scheduling）

基于 **Spring Cloud（JDK 17 / Maven）** 的预测任务平台：对外提供量价、负荷预测接口（数据预处理、模型训练、结果预测、一体化预测），
通过 HTTP 调用 **Python 预测引擎** 执行具体算法；支持超大训练集**分批传输**、预训练数据**两级缓存**、不同**表结构**数据源接入、
基于数据库配置的 **Cron 定时预测**（集群去重、失败重试、结果入库），以及完整的**监控与链路追踪**。

> 详细设计见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，Python 引擎协议见 [forecast-python-engine/README.md](forecast-python-engine/README.md)。

## 技术栈

| 类别 | 选型 |
|---|---|
| 基础框架 | Spring Boot 3.5、Spring Cloud 2025.0、Spring Cloud Alibaba 2025.0（Nacos 注册/配置中心）、JDK 17 |
| 网关 | Spring Cloud Gateway（WebFlux）+ LoadBalancer + Resilience4j 熔断 |
| 持久化 | MyBatis-Plus 3.5、Flyway、MySQL 8（开发/测试使用 H2 MySQL 模式） |
| 缓存 | Caffeine（L1 本地）+ Redis（L2 共享，可选）+ Redis Pub/Sub 失效广播 |
| 引擎调用 | RestClient + Apache HttpClient5 连接池 + Resilience4j 重试/熔断 |
| 可观测性 | Actuator、Micrometer（Prometheus）、Micrometer Tracing（Brave，W3C traceparent）、SSE 实时进度 |
| 文档 | springdoc-openapi（/swagger-ui.html） |
| Python 引擎 | FastAPI、pandas、scikit-learn（参考实现，可替换） |

## 模块

```
forecast-task-scheduling
├── forecast-common          公共模型：统一响应 R、错误码、业务异常、枚举、JSON 工具
├── forecast-api             对外契约：请求/响应 DTO + 声明式 HTTP 客户端（Spring HTTP Interface，供其他微服务依赖）
├── forecast-task-server     预测任务服务（核心）
│   ├── controller           对外接口：预测 / 任务 / 定时任务 / 数据源 / 缓存 / 模型 / 监控
│   ├── pipeline             流程编排：ForecastStep 扩展点、PipelineRegistry、PipelineExecutor、各步骤实现
│   ├── engine               Python 引擎客户端、实例解析与亲和、分批传输（ChunkWriter）、异步任务轮询
│   ├── data                 不同表结构适配（长表 / 日期+时段 / 宽表）、分段读取、两级缓存
│   ├── schedule             DB 驱动的动态 Cron 调度、集群去重、失败重试、错过补偿
│   ├── service              任务提交与执行、状态记录、模型注册表、结果入库（ResultSink 扩展点）
│   ├── monitor              指标、SSE 进度推送、回调通知、心跳/僵尸任务回收、引擎健康检查
│   └── support              全局异常、API Key 鉴权、traceId 响应、演示数据生成
├── forecast-gateway         API 网关：统一入口、鉴权、访问日志、熔断/重试
├── forecast-python-engine   Python 预测引擎（FastAPI）
└── deploy                   docker-compose（MySQL/Redis/Nacos/引擎/服务/网关/Prometheus）、告警规则
```

## 快速开始（本地，无需任何外部依赖）

开发环境（`dev` profile，默认）使用内嵌 H2，启动时自动建表并生成约 120 天的演示数据（3 张不同结构的业务表）。

```bash
# 1. 启动 Python 引擎
cd forecast-python-engine
pip install -r requirements.txt
uvicorn app.main:app --port 8000

# 2. 启动预测任务服务（另开终端，仓库根目录）
mvn -DskipTests package
java -jar forecast-task-server/target/forecast-task-server.jar

# 3. （可选）启动网关
java -jar forecast-gateway/target/forecast-gateway.jar
```

- 接口文档：http://localhost:8080/swagger-ui.html
- 健康检查：http://localhost:8080/actuator/health（含 Python 引擎状态）
- 监控总览：http://localhost:8080/api/v1/monitor/overview

### 调用示例

```bash
# 一体化预测（取数 → 预处理 → 训练 → 预测 → 入库），同步等待最多 120 秒
curl -X POST 'localhost:8080/api/v1/forecast/LOAD/full?waitSeconds=120' \
  -H 'Content-Type: application/json' \
  -d '{"seriesKeys":["AREA_01","AREA_02"],"requestId":"demo-001"}'

# 量价预测（价格 + 电量两个目标），异步提交，立即返回 taskId
curl -X POST localhost:8080/api/v1/forecast/PRICE/full -H 'Content-Type: application/json' -d '{}'

# 仅预测（使用最新可用模型），指定预测窗口
curl -X POST 'localhost:8080/api/v1/forecast/LOAD/predict?waitSeconds=60' -H 'Content-Type: application/json' \
  -d '{"seriesKeys":["AREA_01"],"forecastStart":"2026-10-10T00:00:00","forecastEnd":"2026-10-11T00:00:00"}'

# 先预处理，再用预处理结果训练（跳过取数与预处理）
curl -X POST 'localhost:8080/api/v1/forecast/LOAD/preprocess?waitSeconds=60' -H 'Content-Type: application/json' -d '{}'
curl -X POST 'localhost:8080/api/v1/forecast/LOAD/train?waitSeconds=60' -H 'Content-Type: application/json' \
  -d '{"processedDatasetId":"pd_xxx","algorithm":"rf"}'

# 任务详情（含每个步骤的耗时、Python 任务 ID、产出）、实时进度（SSE）、结果、取消、重试
curl localhost:8080/api/v1/tasks/{taskId}
curl -N localhost:8080/api/v1/tasks/{taskId}/stream
curl 'localhost:8080/api/v1/tasks/{taskId}/results?page=1&size=500'
curl -X POST localhost:8080/api/v1/tasks/{taskId}/cancel
curl -X POST localhost:8080/api/v1/tasks/{taskId}/retry
```

响应统一为 `{"code":0,"message":"success","data":{...},"traceId":"..."}`，`code` 非 0 时见 `ErrorCode`。

## 主要接口

| 分类 | 接口 | 说明 |
|---|---|---|
| 预测 | `POST /api/v1/forecast/{taskType}/{preprocess\|train\|predict\|full}` | `taskType`：LOAD、PRICE（可配置扩展）；`waitSeconds` 同步等待 |
| | `GET /api/v1/forecast/task-types` | 已配置的任务类型、流程步骤 |
| 任务 | `GET /api/v1/tasks/{id}`、`GET /api/v1/tasks` | 详情（步骤轨迹）、分页查询 |
| | `GET /api/v1/tasks/{id}/stream` | SSE 实时进度 |
| | `POST /api/v1/tasks/{id}/cancel`、`/retry` | 取消（同步取消 Python 任务）、按原参数重跑 |
| 结果 | `GET /api/v1/tasks/{id}/results`、`GET /api/v1/results/latest` | 任务结果、某对象最新预测 |
| 定时任务 | `GET/POST/PUT/DELETE /api/v1/jobs`、`/{id}/enable\|disable\|trigger`、`/{id}/fire-logs`、`/cron/preview` | 配置维护、启停、立即触发、触发记录、Cron 预览 |
| 数据源 | `GET/POST/PUT/DELETE /api/v1/data-sources`、`/{code}/preview` | 不同表结构接入与映射预览 |
| 缓存 | `GET /api/v1/cache/stats`、`POST /api/v1/cache/evict`、`POST /api/v1/cache/warmup` | 命中统计、数据补录后失效、预热 |
| 模型 | `GET /api/v1/models`、`POST /api/v1/models/{id}/activate` | 版本与指标、切换/回滚 |
| 监控 | `GET /api/v1/monitor/overview`、`/actuator/prometheus`、`/actuator/health` | 总览、指标、健康 |

### 其他微服务调用（forecast-api）

其他微服务依赖 `forecast-api` 后，通过 `ForecastTaskClient` 调用。它基于 Spring Framework 6 的 HTTP Interface + `RestClient` 实现，**不使用 Feign**。
按服务名经注册中心负载均衡调用时，调用方需引入 `spring-cloud-starter-loadbalancer` 与注册中心客户端（如 Nacos discovery），并声明两个 builder：

```java
// 1) 保留 Boot 默认的 RestClient.Builder：一旦声明任何 RestClient.Builder Bean，Boot 的自动配置就会失效，
//    因此显式声明一个 @Primary 的，供应用中其他 RestClient 使用（不做负载均衡）
@Bean
@Primary
@Scope("prototype")
RestClient.Builder restClientBuilder(RestClientBuilderConfigurer configurer) {
    return configurer.configure(RestClient.builder());
}

// 2) 负载均衡专用 builder：同样经 configurer 配置，保留链路追踪、Jackson 等 Boot 定制
@Bean
@LoadBalanced
@Scope("prototype")
RestClient.Builder loadBalancedRestClientBuilder(RestClientBuilderConfigurer configurer) {
    return configurer.configure(RestClient.builder());
}

@Bean
ForecastTaskClient forecastTaskClient(@LoadBalanced RestClient.Builder builder) {
    // 第三个参数为 API Key（服务端未开启鉴权时可传 null）
    return ForecastTaskClients.create(builder, "http://forecast-task-server", "your-api-key");
}
```

不经注册中心（直连服务地址或网关地址）时，注入普通的 `RestClient.Builder`（Boot 自动配置的或上面的 `@Primary` 那个），传入实际地址即可：
`ForecastTaskClients.create(builder, "http://forecast-gateway:9000", "your-api-key")`。注意不要把直连地址交给 `@LoadBalanced` builder，它会把主机名当作服务名解析。

返回与异常约定：

| 情况 | 结果 |
|---|---|
| 调用成功 | 返回 `code == 0` 的 `R` |
| 服务端业务错误：JSON 响应体为统一结构 `R`，包括 4xx（参数错误、任务不存在、未通过鉴权、队列已满等）与 5xx（系统内部错误、引擎不可用、任务超时等） | **不抛异常**，返回 `code != 0` 的 `R`；即使传入的 builder 自带错误状态处理器（如 `RestClientCustomizer` 统一添加的）也是如此 |
| 其他错误响应（网关/代理错误页、Spring Boot 默认错误 JSON、空响应体、非 JSON 响应体等） | 抛 `ForecastClientException`：`getHttpStatus()` 为 HTTP 状态码，错误码 `REMOTE_CALL_FAILED`；`getMessage()` 只含状态码，请求地址（按服务名调用时为服务名地址，不是实例地址）与响应体片段通过 `getUri()`/`getResponseBody()` 获取，仅用于日志。若传入的 builder 已配置错误状态处理器，则由该处理器优先处理 |
| 网络异常（连接失败、超时、读取响应体中断）、按服务名调用但注册中心无可用实例（含引入 spring-retry 的情形） | 抛 Spring 的 `ResourceAccessException` |

注意：

- 调用方 builder 自带的错误状态处理器**必须抛出异常**。处理器不抛异常时 RestClient 会继续把响应体按 `R` 解码，非 `R` 错误（如 Spring Boot 默认错误 JSON）可能得到 `code == 0` 的 `R`（被误判为成功）或 `null`。
- 业务错误在客户端被视为一次正常返回：`http.client.requests` 指标、链路追踪、负载均衡统计中都记为 200，包在调用处的熔断器/重试也不会将其记为失败。原始状态码放在内部响应头 `X-Forecast-Original-Status`，只有拦截器与观测约定能看到，接口方法的返回值不含该信息；以服务端指标为准。其他错误响应保留真实状态码。

```java
R<TaskSubmitResult> r = forecastTaskClient.submit("LOAD", "full", 0, request);
if (!r.isSuccess()) {
    log.warn("提交失败 code={} message={}", r.getCode(), r.getMessage());
}
```

需要熔断/重试时，在调用处用 Resilience4j 包装。服务端故障引起的业务错误（错误码对应 HTTP 5xx，或未定义的错误码）需要计入熔断时，用 `ForecastTaskClients.isServerError` 判断：

```java
CircuitBreakerConfig config = CircuitBreakerConfig.custom()
        .recordExceptions(ResourceAccessException.class, ForecastClientException.class)
        .recordResult(result -> result instanceof R<?> r && ForecastTaskClients.isServerError(r))
        .build();
```

## 定时任务

定时任务配置存储在 `forecast_job_config`，Cron 为 Spring 6 位格式（秒 分 时 日 月 周），时间窗口通过参数模板相对触发时间计算：

```json
{
  "jobCode": "LOAD_DAY_AHEAD", "jobName": "日前负荷一体化预测",
  "taskType": "LOAD", "mode": "FULL", "cronExpression": "0 0 6 * * *", "timeZone": "Asia/Shanghai",
  "taskParams": "{\"seriesKeys\":[\"AREA_01\"],\"historyDays\":60,\"historyEndOffsetDays\":0,\"forecastStartOffsetDays\":1,\"horizonHours\":24}",
  "concurrentPolicy": "SKIP", "misfirePolicy": "FIRE_ONCE", "maxRetries": 2, "retryIntervalSeconds": 300
}
```

- 修改配置后立即生效（本节点），其他节点在 `forecast.scheduler.refresh-interval`（默认 30s）内同步；
- 多实例部署时以 `(job_id, fire_time, attempt)` 唯一键抢占触发记录，**同一触发点只执行一次**；
- `SKIP`：上一次未结束则跳过；`FIRE_ONCE`：停机期间错过的触发在启动后补偿一次；失败按 `maxRetries` 重试；
- 任务结果写入 `forecast_result`，触发记录与任务状态回写 `forecast_job_fire_log` / `forecast_job_config`。

## 扩展指南

| 需求 | 做法 |
|---|---|
| 新增预测任务类型（如风电、光伏） | `forecast.task-types` 中新增一项：主数据源、外生数据源、目标字段、默认窗口与算法、流程名 |
| 新增预测流程 / 步骤 | 实现 `ForecastStep` 并注册为 Bean，在 `forecast.pipelines.<流程名>.<模式>` 中编排步骤顺序 |
| 接入新业务表 | `POST /api/v1/data-sources` 新增配置（`LONG_TIME` / `DATE_PERIOD` / `WIDE_POINTS`）；特殊结构实现 `LayoutHandler` |
| 结果写入业务表 / 消息队列 | 实现 `ResultSink` 并注册为 Bean |
| 新算法 | Python 引擎 `algorithms.py` 中 `@register("名称")`，请求 `algorithm` 指定 |
| 替换调用方式（gRPC/MQ） | 实现 `PythonEngineClient` 接口替换默认 HTTP 实现 |
| 监控告警 / 通知 | 监听 `TaskEvent` 应用事件；Prometheus 告警规则见 `deploy/alert-rules.yml` |

## 部署

```bash
mvn -DskipTests package
cd deploy && docker compose up -d --build
```

`deploy/docker-compose.yml` 包含 MySQL 8.4、Redis、Nacos 3.0、Python 引擎、预测任务服务（prod profile）、网关与 Prometheus。
生产环境关键配置（环境变量）：

| 变量 | 说明 |
|---|---|
| `SPRING_PROFILES_ACTIVE=prod` | MySQL + Redis 二级缓存 + API Key 鉴权（默认开启） |
| `MYSQL_HOST/MYSQL_PORT/MYSQL_DB/MYSQL_USER/MYSQL_PASSWORD` | 平台库 |
| `REDIS_HOST/REDIS_PASSWORD` | 二级缓存与缓存失效广播 |
| `NACOS_ENABLED=true`、`NACOS_ADDR` | 注册中心 + 配置中心（`forecast-task-server.yaml` 支持热刷新） |
| `ENGINE_BASE_URL` | 不使用注册中心时直连 Python 引擎 |
| `ENGINE_TOKEN` | 服务间令牌（Java 与 Python 两侧一致） |
| `API_KEYS` | 接口 API Key（逗号分隔），也可在 Nacos 中配置 `forecast.security.api-keys` |
| `SCHEDULER_ENABLED` | 是否在本节点运行定时调度（可将调度与接口节点分离） |
| `forecast.business-datasource.*` | 业务数据在独立库时的只读连接池 |

注意事项：

- JVM 时区（`TZ`）应与业务数据时区一致（镜像默认 `Asia/Shanghai`），平台内时间均按本地时间处理；
- Python 引擎多副本时 `ENGINE_DATA_DIR` 需挂载共享存储；Java 侧会把一次任务的所有调用固定到同一引擎实例；
- 生产环境默认关闭 Swagger（`SPRINGDOC_ENABLED=true` 可开启），`/actuator` 仅 health/info/prometheus 免鉴权。

## 测试

```bash
mvn test                                   # Java：单元测试 + 集成测试（H2 + 协议一致的引擎替身）
cd forecast-python-engine && pytest -q     # Python 引擎接口测试
```
