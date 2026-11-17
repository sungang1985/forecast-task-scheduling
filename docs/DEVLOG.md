# 开发日志（每日进度与详情）

> 计划见 [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)。本文每天随代码一起提交：上半部分是进度总览，下半部分是每天的开发详情。
> 每天对应一个提交，提交标题以 `Day NN:` 开头，可用 `git log --oneline` 对照。

## 进度总览

**当前进度：15 / 15 天（100%）**

`███████████████`

| 天 | 阶段 | 主题 | 状态 | 当天新增文件 | 当天新增代码行 | 累计代码行 | 自动化测试（累计通过） |
|---|---|---|---|---|---|---|---|
| [D1](#day-01) | 一、基础底座 | 工程骨架与公共模块 | ✅ 完成 | 14 | +515 | 515 | –（仅构建） |
| [D2](#day-02) | 一、基础底座 | 接口契约与服务骨架 | ✅ 完成 | 14 | +630 | 1145 | –（构建 + 手工冒烟） |
| [D3](#day-03) | 一、基础底座 | 持久化与任务记录 | ✅ 完成 | 9 | +634 / −1 | 1778 | –（构建 + 手工冒烟） |
| [D4](#day-04) | 二、数据与引擎 | 多表结构数据源 | ✅ 完成 | 18 | +1291 / −1 | 3068 | 7 |
| [D5](#day-05) | 二、数据与引擎 | 分段缓存与取数服务 | ✅ 完成 | 11 | +1020 | 4088 | 8 |
| [D6](#day-06) | 二、数据与引擎 | Python 引擎（上）：协议与存储 | ✅ 完成 | 12 | +878 | 4966 | Java 8 + Python 3 |
| [D7](#day-07) | 二、数据与引擎 | Python 引擎（下）：算法流程 | ✅ 完成 | 10 | +923 / −8 | 5881 | Java 8 + Python 8 |
| [D8](#day-08) | 二、数据与引擎 | Java 引擎客户端 | ✅ 完成 | 9 | +725 / −1 | 6605 | Java 8 + Python 8 |
| [D9](#day-09) | 三、编排与调度 | 流程编排框架 | ✅ 完成 | 11 | +790 | 7395 | Java 13 + Python 8 |
| [D10](#day-10) | 三、编排与调度 | 分批传输与内置步骤 | ✅ 完成 | 18 | +1546 | 8941 | Java 15 + Python 8 |
| [D11](#day-11) | 三、编排与调度 | 任务提交与对外接口 | ✅ 完成 | 12 | +1497 | 10438 | Java 23 + Python 8 |
| [D12](#day-12) | 三、编排与调度 | 定时调度 | ✅ 完成 | 10 | +1133 | 11571 | Java 27 + Python 8 |
| [D13](#day-13) | 四、可观测与交付 | 监控、可靠性与安全 | ✅ 完成 | 6 | +519 / −1 | 12089 | Java 28 + Python 8 |
| [D14](#day-14) | 四、可观测与交付 | 微服务化 | ✅ 完成 | 10 | +1072 | 13161 | Java 43 + Python 8 |
| [D15](#day-15) | 四、可观测与交付 | 部署与交付 | ✅ 完成 | 6 | +479 / −7 | 13633 | Java 43 + Python 8 |

说明：代码行统计不含 `docs/` 下的计划与日志；“自动化测试”为当天构建实际运行并通过的用例数（Java 为 Maven Surefire，Python 为 pytest）。

## 每日详情

<a id="day-01"></a>
### Day 01 · 工程骨架与公共模块

**目标**：把工程搭起来，并先定下所有模块都要遵守的公共契约（响应结构、错误码、状态枚举）。

**完成内容**

| 交付物 | 说明 |
|---|---|
| `pom.xml`（父工程） | 继承 `spring-boot-starter-parent 3.5`，Java 17；统一编译参数（保留方法参数名、Lombok 与配置元数据注解处理器）、测试输出编码；目前只挂 `forecast-common` 一个模块 |
| `R<T>` | 统一响应：`code`（0 为成功）、`message`、`data`、`traceId`、`timestamp`，提供 `ok` / `fail` 工厂方法 |
| `PageResult<T>` | 分页结果：总数、页码、页大小、记录列表 |
| `ErrorCode` | 错误码分段：1xxxx 通用、2xxxx 任务、3xxxx 数据、4xxxx Python 引擎、5xxxx 调度；每个错误码绑定一个 HTTP 状态码，并可按数值反查 |
| `BizException` | 携带 `ErrorCode` 的业务异常 |
| `TaskStatus` / `StepStatus` / `ForecastMode` / `TriggerType` | 任务状态（含“是否终态”判断）、步骤状态、执行模式（PREPROCESS / TRAIN / PREDICT / FULL）、触发方式（API / SCHEDULE / MANUAL / RETRY） |
| `JsonUtils` | 实体中 JSON 字段的序列化工具（支持 Java 8 时间类型） |
| `TraceHeaders` | 追踪相关的请求头与日志 MDC 键：`X-Trace-Id`、`X-Task-Id`、`X-API-Key` |
| `.gitignore`、`README.md` | 忽略构建产物与运行时目录；README 先放计划与日志入口 |

**关键设计**

- **错误码自带 HTTP 状态码**。后面全局异常处理只需要读 `errorCode.getHttpStatus()`，不用再维护一张映射表；客户端也能据此区分“调用方的错”（4xx）和“服务端的错”（5xx）。
- **错误码一次定全**。虽然引擎、调度相关的功能要到第二、三周才做，但错误码是对外契约，先分好段可以避免后期编号冲突。
- **公共模块不依赖 Spring**。`forecast-common` 只依赖 Jackson、Validation API 和 Lombok，其他微服务引用它不会带入多余依赖。

**验证**

- `mvn verify`：构建成功（1 个模块）。
- 当天没有业务逻辑，未写单元测试；这些类从明天起被服务模块使用，间接得到覆盖。

**遗留与明日计划**：明天建立 `forecast-api`（对外 DTO）与 `forecast-task-server`（可启动的服务骨架）。

<a id="day-02"></a>
### Day 02 · 接口契约与服务骨架

**目标**：定下对外的请求/响应结构，并让预测任务服务作为一个空壳先跑起来。

**完成内容**

| 交付物 | 说明 |
|---|---|
| `forecast-api` 模块 | 对外契约模块，其他微服务只依赖它即可调用本平台 |
| `ForecastTaskRequest` | 预处理 / 训练 / 预测 / 一体化通用的提交请求：幂等号、预测对象、历史窗口、预测窗口、目标字段、算法与参数、模型 ID、回调地址、超时 |
| `TaskSubmitResult` / `TaskDetail` / `TaskStepDetail` / `ForecastPointVO` | 提交结果、任务详情（含步骤轨迹）、步骤记录、预测结果点 |
| `forecast-task-server` 模块 | Web + 参数校验 + Actuator + Micrometer Tracing；打包为可执行 jar 并生成构建信息 |
| `ForecastTaskApplication` | 启动类，开启配置属性扫描 |
| `application.yml` | 端口、优雅停机、Jackson 时区与日期格式、健康检查探针（liveness / readiness）、指标公共标签、追踪采样率 |
| `ForecastProperties` | 平台配置入口（前缀 `forecast`），今天只有 `node-id`，后续每天按功能追加 |
| `NodeIdentity` | 节点标识（配置优先，否则 `主机名:端口`），后面写入任务执行记录 |
| `GlobalExceptionHandler` | 业务异常、参数校验、请求体不可读、资源不存在、兜底异常统一转成 `R`，HTTP 状态码取自错误码 |
| `TraceResponseAdvice` | 给每个 `R` 响应填上 `traceId`，并写入响应头 `X-Trace-Id` |
| `logback-spring.xml` | 日志格式带 `[应用名, traceId, spanId, taskId]`；生产环境按天 + 大小滚动 |

**关键设计**

- **契约单独成模块**。DTO 放在 `forecast-api` 而不是服务内部，是为了 D14 的声明式客户端与服务端共用同一份类型，避免两边各写一套。
- **traceId 第一天就接入**。所有响应和日志从一开始就带链路 ID，后面排查任何问题都有据可查；等功能多了再补会漏。
- **配置集中到一个类**。所有业务配置都挂在 `ForecastProperties` 下，接入配置中心后可以整体热刷新。

**验证**

- `mvn verify`：3 个模块构建成功。
- 手工启动服务（端口 18080）：
  - `GET /actuator/health` 返回 `{"status":"UP"}`，含 liveness / readiness 分组；
  - `GET /not-exists` 返回 HTTP 404，响应体为 `{"code":10404,"message":"…","traceId":"6afc1596…"}`，说明全局异常处理与 traceId 填充生效；
  - 启动日志无 ERROR。

**遗留与明日计划**：服务还没有数据库。明天接入 MyBatis-Plus 与 Flyway，建任务实例表和步骤表。

<a id="day-03"></a>
### Day 03 · 持久化与任务记录

**目标**：让任务“有据可查”——每个任务、每个步骤的状态都落库，并且状态流转在并发下是安全的。

**完成内容**

| 交付物 | 说明 |
|---|---|
| 依赖 | MyBatis-Plus（含分页所需的 jsqlparser）、JDBC、Flyway（含 MySQL 方言）、MySQL 驱动、H2 |
| `V1__init_schema.sql` | `forecast_task_instance`（任务实例：状态、进度、当前步骤、触发方式、幂等号、traceId、执行节点、请求参数、结果摘要、错误、取消标记、回调、心跳…）与 `forecast_task_step`（步骤轨迹：顺序、状态、尝试次数、引擎任务 ID、产出、耗时） |
| `TaskInstance` / `TaskStep` + Mapper | 两张表的实体与 MyBatis-Plus Mapper |
| `MybatisPlusConfig` | 分页插件（单页上限 1000）、乐观锁插件、`createdAt` / `updatedAt` 自动填充 |
| `TaskRecordService` | 任务与步骤记录的全部写操作：创建、置为运行、更新进度（1 秒节流）、结束、请求取消、心跳、回收超时任务、步骤开始 / 结束 / 跳过 |
| `TaskQueryService` | 任务详情（含步骤轨迹）与多条件分页查询，实体转 `TaskDetail` |
| `application-dev.yml` | 开发环境用内嵌 H2 文件库（MySQL 兼容模式），不依赖任何外部组件 |
| `application.yml` | Flyway 开启、MyBatis-Plus 下划线转驼峰与雪花 ID、就绪探针加入数据库检查 |

**关键设计**

- **状态流转一律用“带前置状态的条件更新”**。例如置为运行是 `UPDATE … SET status='RUNNING' WHERE task_id=? AND status='PENDING'`，更新行数为 0 就说明任务已被取消或被别的线程处理。这样不需要加锁，多实例下也不会互相覆盖状态。
- **结束状态不可被覆盖**。`markFinished` 只在任务仍处于活动状态时生效，避免执行线程把“已取消 / 已被回收”的任务又改成成功。
- **唯一索引承担幂等**。`request_id` 上建唯一索引，D11 的重复提交去重直接依赖数据库约束。
- **进度写库节流**。引擎侧进度可能每秒上报多次，这里限制为每个任务每秒最多写一次，步骤切换时强制写。
- **开发库用 H2 的 MySQL 模式**。建表脚本只写一份 MySQL 语法，开发、测试、生产共用。

**验证**

- `mvn verify`：构建成功。
- 手工启动服务：
  - Flyway 日志显示 `Migrating schema "public" to version "1 - init schema"`、`Successfully applied 1 migration`；
  - `GET /actuator/health/readiness` 返回 UP，且包含 `db` 组件（H2）；
  - 启动日志无 ERROR。
- 记录服务的各状态流转方法今天没有独立单测，D9 的执行器单测与 D11 的集成测试会覆盖到。

**遗留与明日计划**：发布前所有表结构都合并在 `V1` 一个基线脚本里，后面几天新增的表也追加到这里（上线后才改为 `V2`、`V3` 增量脚本）。明天做数据源配置与三种表结构的适配器。

<a id="day-04"></a>
### Day 04 · 多表结构数据源

**目标**：业务方的历史数据表结构各不相同，平台要用“配置”而不是“写代码”的方式接入，并统一成 `(series_key, ts, fields…)` 这一种形态。

**完成内容**

| 交付物 | 说明 |
|---|---|
| `forecast_data_source` 表 | 数据源元数据：表名、布局、预测对象列、时间列 / 日期列 / 时段列、每日点数、字段映射、固定过滤条件、缓存分段粒度 |
| `DataSourceConfig` + Mapper | 上表的实体与 Mapper |
| `DataSourceDefinition` | 校验并解析后的不可变数据源定义：按布局检查必填列、解析字段映射与固定过滤的 JSON |
| `SqlIdentifiers` | 表名 / 列名白名单正则校验 |
| `DatasetLayout` | 三种布局：`LONG_TIME`、`DATE_PERIOD`、`WIDE_POINTS` |
| `LayoutHandler` + 3 个实现 | 长表（一行一个时间点）、日期 + 时段序号（时段 p 对应 `date + (p-1) × 步长`）、宽表（一行一天，`p%03d` 生成 p001…p096）；公共基类负责拼装“时间范围 + 预测对象 + 固定过滤”的参数化 SQL |
| `DataRow` | 统一数据行：时间点 + `double[]`（缺失值用 NaN） |
| `SegmentUnit` | 分段粒度（DAY / MONTH）与对齐、遍历算法，明天的缓存按它切段 |
| `DataSourceRegistry` | 启动时加载并校验全部数据源，之后每分钟刷新；新接入一张表只需插入一行配置 |
| `DataJdbcConfig` | 取数专用 `NamedParameterJdbcTemplate`；配置了 `forecast.business-datasource.url` 时使用独立只读连接池 |
| 演示数据 | `R__demo_source_tables.sql` 建三张结构不同的演示表并登记为数据源；`DemoDataGenerator` 生成带日内曲线、周特性、温度敏感性的合成数据并每日补齐 |
| 测试 | `DataSourceDefinitionTest`（4 个）、`SegmentUnitTest`（3 个） |

三张演示表正好覆盖三种布局：

| 数据源 | 表 | 布局 | 字段映射 |
|---|---|---|---|
| `load_curve_96` | `ods_load_curve_96` | 宽表 | `{"load":"p%03d"}` |
| `market_price_da` | `ods_market_price` | 日期 + 时段 | `{"price":"clearing_price","volume":"clearing_volume"}`，固定过滤 `{"price_type":"DA"}` |
| `weather_hourly` | `ods_weather_hourly` | 长表 | `{"temperature":"temperature","humidity":"humidity"}` |

**关键设计**

- **防 SQL 注入**。表名和列名来自配置，必须拼进 SQL，所以先过白名单正则；所有取值（时间范围、预测对象、过滤值）一律参数绑定。
- **新增表结构的扩展点**。实现 `LayoutHandler` 并注册为 Spring Bean 即可，注册表按布局枚举自动找到对应的处理器。
- **业务库与平台库隔离**。业务数据常在另一个库里，独立连接池可以保证大查询不会占满平台库连接、影响任务状态更新。
- **演示脚本用可重复迁移**（`R__` 前缀）并保持幂等，只在 dev 的 Flyway 路径里加载，生产不带。

**验证**

- `mvn verify`：构建成功，**7 个测试全部通过**。
- 手工启动服务，日志显示：
  - `Migrating schema "public" with repeatable migration "demo source tables"`；
  - `演示数据已补齐：气象 8856 行，负荷 360 天·区域，量价 23040 行`；
  - `已加载数据源 3 个: [load_curve_96, market_price_da, weather_hourly]`。

**遗留与明日计划**：读库参数（`fetch-size`、单次 `IN` 查询的 key 数上限）先放在 `forecast.cache` 配置节里，明天补齐缓存参数。目前还没有对外的取数入口，明天做分段缓存、取数服务和预览接口。

<a id="day-05"></a>
### Day 05 · 分段缓存与取数服务

**目标**：训练和滚动预测会反复读取相近的历史窗口，要让这些读取尽量不落到数据库上，同时内存占用可控。

**完成内容**

| 交付物 | 说明 |
|---|---|
| 依赖 | Caffeine、Spring Data Redis |
| `SegmentKey` | 缓存键：数据源 + 预测对象（`*` 表示全部）+ 分段时间范围 |
| `DataSegment` | 一个分段的数据：字段名 + 各预测对象按时间升序的数据行 |
| `SegmentCache` | 两级缓存：L1 Caffeine（按行数加权淘汰）→ L2 Redis → 数据库；统计命中、未命中、读库次数 |
| `SegmentCodec` | 分段的二进制 + gzip 编解码，供 Redis 使用 |
| `RedisSegmentStore` / `RedisCacheConfig` | Redis 读写与失效广播（Pub/Sub），仅 `forecast.cache.redis-enabled=true` 时装配 |
| `TrainingDataService` | 取数服务：把请求窗口拆成分段 → 逐段读取（优先缓存）→ 裁剪到窗口 → 流式回调 |
| `SeriesSlice` | 流式回调的一段序列数据 |
| `DataSourceController` | `/api/v1/data-sources`：增删改查与**数据预览**（验证表结构配置是否正确） |
| `CacheController` | `/api/v1/cache`：统计、按数据源 / 日期范围失效、预热 |
| `ForecastProperties.Cache` | 本地最大行数、历史 / 近期分段 TTL、近期窗口、Redis 开关与键前缀、失效广播频道 |
| 测试 | `SegmentCodecTest`（1 个） |

**关键设计**

- **按时间分段缓存，而不是按请求缓存**。请求 `[11-01, 11-17)` 和 `[11-02, 11-18)` 的窗口不同，但落在同一个月分段里，第二次请求直接复用。按请求参数做键的话，滚动预测每次都会缓存未命中。
- **近期分段短 TTL，历史分段长 TTL**。三天以内的数据可能还在补录，缓存 5 分钟；更早的数据已稳定，缓存 12 小时。数据修正后可调用失效接口立即生效。
- **防击穿**。用 Caffeine 的 `AsyncCache` 合并同一分段的并发加载；多个缺失分段合并成批量 `IN` 查询；空分段也缓存。
- **Redis 只是加速手段**。Redis 的任何异常都按“未命中”处理，绝不让缓存故障变成任务失败。
- **二进制编码而非 JSON**。体积更小、没有浮点精度损失，并且能原样保留 NaN 缺失值（JSON 无法表示 NaN）。
- **内存与窗口长度无关**。读取按分段流式回调，调用方处理完一段再读下一段。

**验证**

- `mvn verify`：构建成功，**8 个测试全部通过**。
- 手工启动服务后调用预览接口（窗口 `2026-11-10` 一天，`limit=2`），三种表结构都返回了统一格式：

| 数据源 | 布局 | 返回的首行 |
|---|---|---|
| `load_curve_96` | 宽表 | `{"ts":"2026-11-10T00:00:00","series_key":"AREA_01","load":506.784}` |
| `market_price_da` | 日期 + 时段 | `{"ts":"2026-11-10T00:00:00","series_key":"MARKET_A","price":376.571,"volume":391.654}` |
| `weather_hourly` | 长表 | `{"ts":"2026-11-10T00:00:00","series_key":"AREA_01","temperature":10.36,"humidity":85.58}` |

- 缓存统计：三次预览后 `dbLoadedSegments=3`、`localMissCount=3`；重复请求负荷数据后 `localHitCount=1` 而 `dbLoadedSegments` 仍为 3，说明第二次没有读库。
- 不存在的数据源返回 HTTP 404 与业务码 `30404`。

**遗留与明日计划**

- 冒烟时发现一个现象：演示数据是在应用就绪后才生成的，生成完成前查询量价数据会得到空结果，并且这个空分段会被缓存（近期分段 5 分钟）。只影响开发环境刚启动的几秒钟，等演示数据就绪后再查即正常，记录在此，不作处理。
- Redis 二级缓存本机没有 Redis，今天只验证了关闭状态下的降级路径；开启状态需要在有 Redis 的环境里另行验证。
- 明天开始做 Python 预测引擎。

<a id="day-06"></a>
### Day 06 · Python 引擎（上）：协议与存储

**目标**：把 Java 与 Python 之间的 HTTP 协议定下来，并先实现其中最容易出问题的一段——大数据集的分批上传。

**完成内容**

| 交付物 | 说明 |
|---|---|
| `requirements.txt` / `requirements-dev.txt` | FastAPI、uvicorn、pydantic、pandas、numpy、scikit-learn、joblib、httpx；开发另加 pytest |
| `app/config.py` | 环境变量配置：数据目录、工作线程数、临时数据与模型保留时长、单分片上限、访问令牌、Nacos 地址 |
| `app/logging_context.py` | 用 `contextvars` 把 traceId / taskId 注入每条日志，格式与 Java 侧一致 |
| `app/schemas.py` | **全部协议模型**（snake_case）：数据集创建 / 完成、预处理 / 训练 / 预测请求、任务引用与状态、预测结果分页、健康信息 |
| `app/storage.py` | 文件存储：数据集、分片、预处理结果、模型、预测结果、任务状态；ID 与分区名白名单校验；过期清理 |
| `app/tasks.py` | 异步任务管理器：线程池执行、`request_id` 去重、进度上报、协作式取消、状态落盘 |
| `app/main.py` | FastAPI 应用：令牌鉴权中间件、链路上下文中间件、统一 404 / 400 错误；数据集接口（创建、上传分片、完成合并、删除）；任务状态查询与取消；健康检查；后台定时清理 |
| `tests/` | `test_checksum_and_missing_chunk`、`test_path_traversal_rejected`、`test_health`（3 个） |

分批上传协议：

| 步骤 | 接口 | 校验 |
|---|---|---|
| 创建会话 | `POST /api/v1/datasets` | 必须有 `primary` 分区；每个分区前两列固定为 `ts, series_key` |
| 上传分片 | `PUT /api/v1/datasets/{id}/parts/{part}/chunks/{seq}` | 大小上限、CRC32（请求头 `X-Chunk-Checksum`）、列与声明一致、行数与 `X-Chunk-Rows` 一致 |
| 完成合并 | `POST /api/v1/datasets/{id}/complete` | 缺片时返回 `missing_chunks`；合并后核对总行数 |
| 删除 | `DELETE /api/v1/datasets/{id}` | – |

**关键设计**

- **分片上传幂等**。分片按 `(数据集, 分区, 序号)` 落成独立文件，先写临时文件再原子改名；同一序号重复上传只是覆盖，所以 Java 侧可以放心重试。
- **完整性三重校验**。每片校验 CRC32 与行数，完成时再检查缺片和总行数，任何一步不符都明确报错而不是悄悄少数据。
- **路径穿越防护**。所有 ID 和分区名在拼路径之前先过正则白名单，`../` 之类的输入直接返回 400。
- **长任务一律异步**。训练可能跑几分钟到几十分钟，HTTP 请求不能一直挂着；提交即返回 `task_id`，调用方轮询。任务管理器今天先就位，具体的预处理 / 训练 / 预测明天接入。
- **令牌比较用常量时间算法**（`hmac.compare_digest`），健康检查免鉴权。

**验证**

- `pytest`：**3 个测试全部通过**（错误的 CRC 返回 422、行数不符返回 400、声明 3 片只传 1 片时返回 `missing_chunks={"primary":[1,2]}`、非法 ID 返回 400、健康检查返回 UP）。
- 手工脚本验证成功路径：24 行数据分 3 片上传均返回 204；重复上传第 0 片仍返回 204；`complete` 返回 `{"rows":{"primary":24},"missing_chunks":{}}`；删除返回 204。
- Java 侧未改动，`mvn verify` 仍为 8 个测试通过。

**遗留与明日计划**：计划调整一处——协议文档（引擎 README）改到明天与算法流程一起写，今天以协议模型和测试为准。令牌鉴权的自动化测试也在明天补上。明天实现预处理、训练、预测。

<a id="day-07"></a>
### Day 07 · Python 引擎（下）：算法流程

**目标**：让 Python 引擎能独立跑通“上传数据 → 预处理 → 训练 → 预测 → 取结果”的完整流程。

**完成内容**

| 交付物 | 说明 |
|---|---|
| `pipeline/preprocess.py` | 去重 → 规整到目标频率的时间网格 → 负值剔除 → 异常值截断（负荷用 IQR、价格用分位数）→ 缺失值填补（短缺口插值、长缺口用前一天同时段）→ 外生变量重采样并对齐 |
| `pipeline/features.py` | 特征：时段、星期、月份、年内周期 + 目标滞后项 + 外生变量 |
| `pipeline/algorithms.py` | 算法注册表：`gbrt`（直方图梯度提升）、`rf`（随机森林）、`linear`（标准化 + 岭回归）、`naive`（滞后值基线） |
| `pipeline/train.py` | 每个（预测对象，目标）训练一个模型；按时间切出最后 15% 做验证，计算 MAE / RMSE / MAPE，再全量重训；残差标准差用于 95% 区间 |
| `pipeline/predict.py` | 对近期历史做同样的预处理，以最短滞后为步长分块递推预测 |
| `app/main.py` | 新增 `POST /api/v1/preprocess`、`/train`、`/predict`（异步提交）、`GET /api/v1/predictions/{id}`（分页）、`/models/{id}`、`/algorithms` |
| `app/registry.py` | 配置了 `NACOS_ADDR` 时启动注册、停止注销，服务名 `forecast-python-engine` |
| `Dockerfile` / `.dockerignore` | 基于 `python:3.13-slim`，非 root 用户运行，带健康检查，数据目录挂载为卷 |
| `README.md` | 引擎的运行方式、环境变量、**完整接口协议**、处理流程与扩展方式 |
| 测试 | 新增 `test_full_flow`、`test_request_id_dedupe`、`test_unknown_algorithm`、`test_engine_token`、`test_resolve_lags`，共 8 个 |

**关键设计**

- **预测时的滞后特征用递推**。预测明天 96 个点时，“一天前的值”对前 96 个点是已知历史，但如果预测窗口更长或者历史与预测起点之间有空档，滞后值本身还没发生。做法是以最短滞后为步长分块预测，后一块的滞后特征使用前一块的预测值。
- **外生变量可以与主数据不同频、不同键**。逐小时气象先插值到 15 分钟再对齐；`join_on_key=true` 按 `(series_key, ts)` 关联，`false` 只按 `ts` 关联（多个键取均值），量价预测引用全网负荷就是后一种。
- **验证集按时间切分而不是随机切分**。时间序列随机切分会让模型“看到未来”，指标虚高。
- **算法是可替换的**。用 `@register("名称")` 注册工厂函数即可新增算法，Java 侧通过请求里的 `algorithm` 选择。仓库里的实现定位是协议完整、流程完整的参考实现。
- **`lags` 参数的容错**。Java 的配置绑定可能把列表 `[96, 672]` 变成 `{"0":96,"1":672}`，`resolve_lags` 两种形式都接受，并拒绝非法值。

**验证**

- `pytest`：**8 个测试全部通过**（3.6 秒）。其中 `test_full_flow` 用 30 天、2 个区域的合成负荷数据走完全流程，并断言：
  - 预处理确实填补了缺失值，外生列 `weather__temperature` 已对齐进来；
  - 训练的验证集 MAPE 小于 10%；
  - 历史与预测起点之间留一天空档时，仍能预测出 2 × 96 = 192 行；
  - 结果分页正确（100 + 92），且 `lower ≤ value ≤ upper`，首个预测值与真实曲线的偏差小于 20%。
- 其余用例覆盖：相同 `request_id` 重复提交返回同一任务、未知算法返回 400、令牌缺失或错误返回 401。
- Java 侧未改动，`mvn verify` 仍为 8 个测试通过。

**遗留与明日计划**：Nacos 注册与 Docker 镜像今天只写了代码，没有可用的 Nacos 和 Docker 环境，未实际运行验证。明天回到 Java 侧，实现调用引擎的客户端。

<a id="day-08"></a>
### Day 08 · Java 引擎客户端

**目标**：Java 服务能稳定地调用 Python 引擎——有连接池、会重试、能熔断、知道该连哪个实例。

**完成内容**

| 交付物 | 说明 |
|---|---|
| 依赖 | Spring Cloud 与 Spring Cloud Alibaba BOM、Nacos 服务发现、Resilience4j、Apache HttpClient 5 |
| `EngineDtos` | 与引擎协议一一对应的请求 / 响应对象（Java record） |
| `PythonEngineClient` | 引擎客户端抽象：健康检查、数据集创建 / 上传分片 / 完成 / 删除、提交预处理 / 训练 / 预测、查询与取消任务、分页取结果 |
| `HttpPythonEngineClient` | HTTP 实现。统一调用入口按“异常翻译 → 熔断 → 重试 → 指标”的顺序包装每次调用，记录 `forecast_engine_calls_seconds{operation,outcome}` |
| `EngineClientConfig` | HttpClient 5 连接池；专用的 snake_case `ObjectMapper`；拦截器透传 `X-Trace-Id`、`X-Task-Id`、`X-Engine-Token`；重试（指数退避）与熔断器 |
| `EngineCallException` / `EngineTaskFailedException` | 调用异常（带“是否可重试”标记）与引擎任务执行失败异常 |
| `EngineEndpoint` / `EngineEndpointResolver` | 引擎实例地址及其解析：配置了 `forecast.engine.base-url` 则直连，否则从注册中心按服务名发现并轮询 |
| `PythonEngineHealthIndicator` | 把引擎健康状况纳入 `/actuator/health`（组件名 `pythonEngine`），附带熔断器状态 |
| `ForecastProperties.Engine` | 地址、令牌、服务名、超时、连接池、轮询间隔、重试与熔断参数 |
| 配置 | `spring.cloud.nacos.discovery`（默认关闭）；dev 环境用 simple discovery 声明本地引擎实例；Actuator 暴露 `retries`、`circuitbreakers` 端点 |

**关键设计**

- **只对瞬时故障重试和计入熔断**。网络异常、5xx、429 标记为可重试；4xx 是请求本身有问题，重试没有意义，也不应把熔断器打开。
- **重试是安全的**。所有提交类接口都带 `request_id`，引擎侧去重（D6 已实现），上传分片本身幂等，所以客户端重试不会产生重复任务或重复数据。
- **客户端是接口**。流程编排只依赖 `PythonEngineClient`，以后改成 gRPC 或消息队列只需换一个实现。
- **引擎不在就绪探针里**。引擎不可用时服务仍然是就绪的（还能查任务、管配置），只是引擎健康组件显示 DOWN；否则引擎一抖动，所有服务实例都会被摘流量。
- **开发环境不依赖 Nacos**。Nacos 默认关闭，dev 用 Spring Cloud 的 simple discovery 把 `forecast-python-engine` 指到 `localhost:8000`；开启 Nacos 后以 Nacos 的实例为准。

**验证**

- `mvn verify`：构建成功，Java 8 个测试通过（今天没有新增自动化测试）。
- 手工启动服务，**不启动引擎**：
  - `GET /actuator/health/pythonEngine` 返回 HTTP 503，`{"status":"DOWN","details":{"error":"…Connection refused","circuitBreaker":"CLOSED"}}`；
  - `GET /actuator/health/readiness` 仍为 UP；
  - `GET /actuator/circuitbreakers` 能看到 `pythonEngine` 熔断器，阈值 50%。
- **同时启动 D7 的 Python 引擎**（端口 8000）：`GET /actuator/health/pythonEngine` 返回 `{"status":"UP","details":{"endpoint":"http://localhost:8000","version":"1.0.0","workers":4,…}}`。这是 Java 与 Python 第一次真实连通。

**遗留与明日计划**：客户端的各业务方法今天只通过健康检查间接验证了连通性和 JSON 协议，逐个方法的验证在 D11 的集成测试里。明天做流程编排框架。

<a id="day-09"></a>
### Day 09 · 流程编排框架

**目标**：把“一次预测任务要按什么顺序做哪些事”抽象成可配置的流程，并把重试、取消、超时、记录这些横切逻辑集中到一个执行器里，步骤本身只写业务。

**完成内容**

| 交付物 | 说明 |
|---|---|
| `ForecastStep` | 步骤扩展点：`name()`、`description()`、`shouldSkip(ctx)`、`retryable()`、`execute(ctx)` |
| `StepResult` | 步骤结果：说明、产出（写入步骤记录）、对应的引擎任务 ID |
| `PipelineContext` | 贯穿一次任务的上下文：补全后的请求、步骤间产物、取消标记、截止时间、进度监听、清理动作、绑定的引擎实例 |
| `ContextKeys` | 步骤间传递产物的键：数据集 ID、预处理数据集 ID、模型 ID、预测结果 ID 等 |
| `PipelineRegistry` | 收集容器里全部 `ForecastStep`，按（任务类型，模式）从配置解析出有序步骤；**启动时校验**配置引用的步骤和流程都存在 |
| `TaskTypeRegistry` | 任务类型注册表（来自 `forecast.task-types`） |
| `PipelineExecutor` | 执行器：按序执行、跳过判断、步骤级重试与退避、步骤边界的取消 / 超时检查、步骤轨迹落库、Micrometer Observation、无论成败都执行清理 |
| `TaskCancelledException` / `TaskTimeoutException` | 取消与超时的专用异常 |
| `TaskEvent` | 任务生命周期事件：提交、开始、步骤开始 / 进度 / 结束、任务结束 |
| `ForecastProperties` | 新增 `task`（默认超时、同步等待上限、心跳、步骤重试次数与退避）、`task-types`、`pipelines` 及其嵌套类型 |
| 测试 | `PipelineExecutorTest`（5 个） |

**关键设计**

- **步骤只管业务，执行器管其余一切**。新增一个步骤不需要关心重试、记录、指标，写一个实现类、在配置里引用名字即可。
- **哪些错误值得重试**。引擎调用异常看它自带的可重试标记；引擎任务执行失败（算法或数据问题）不重试；其他业务异常（参数非法、数据为空、模型不存在）不重试；未知异常允许重试。重试前的退避期间仍会响应取消。
- **清理一定执行**。步骤可以向上下文登记清理动作（比如删除引擎上的临时数据集），执行器在 `finally` 里逐个执行，清理失败只记日志、不影响任务结果。
- **用事件解耦监控**。执行器只负责发布 `TaskEvent`，SSE 推送、回调、指标、定时任务回写以后都以监听者的方式接入，不改执行链路。
- **配置错误在启动时暴露**。流程引用了不存在的步骤会让服务直接启动失败，而不是等到某个任务跑到一半才报错。

**验证**

- `mvn verify`：构建成功，**Java 13 个测试全部通过**。`PipelineExecutorTest` 的 5 个场景：

| 用例 | 验证点 |
|---|---|
| `runsStepsInOrderSkipsAndRunsCleanup` | 步骤按序执行，`shouldSkip` 的步骤被跳过，清理动作被执行 |
| `retriesTransientEngineFailureThenSucceeds` | 引擎瞬时故障后自动重试并成功 |
| `businessErrorIsNotRetried` | 业务错误不重试 |
| `cancelledContextStopsBeforeNextStepAndStillCleansUp` | 取消后在下一步之前停止，且仍执行清理 |
| `expiredDeadlineRaisesTimeout` | 超过截止时间抛出超时 |

- 手工启动服务：日志显示 `已注册流程步骤: []，流程: []`，服务就绪。

**遗留与明日计划**：目前还没有任何步骤实现，所以流程与任务类型的配置（`forecast.pipelines`、`forecast.task-types`）故意没有写进 `application.yml`——写了会因为引用的步骤不存在而启动失败。明天实现五个内置步骤后一并加上。

<a id="day-10"></a>
### Day 10 · 分批传输与内置步骤

**目标**：给昨天的编排框架填上真正的步骤——取数并分批传给引擎、预处理、训练、预测、结果入库。

**完成内容**

| 交付物 | 说明 |
|---|---|
| `ChunkWriter` | 把流式数据切成固定行数的分片，用 Jackson 流式写出紧凑的列式 JSON 并可 gzip：`{"columns":["ts","series_key","load"],"rows":[[…],…]}` |
| `DatasetTransferService` | 分批传输：创建数据集会话 → 逐段读取（走 D5 的缓存）→ 切片 → gzip + CRC32 → 有界并发上传 → 完成校验；统计 `forecast_transfer_bytes_total` / `forecast_transfer_chunks_total` |
| `EngineTaskAwaiter` | 等待引擎异步任务：指数退避轮询、同步引擎侧进度、取消 / 超时时主动通知引擎取消 |
| `ExecutorConfig` | 两个隔离的线程池：任务执行池（有界队列 + 拒绝策略）与数据传输池（调用方兜底执行）；都带 MDC 传递，保证异步线程的日志也有 traceId |
| `LoadDataStep` | 取数并上传：主数据源读历史窗口，外生数据源按配置决定是否覆盖预测时段 |
| `PreprocessStep` / `TrainStep` / `PredictStep` | 提交引擎任务并等待；训练成功后登记模型 |
| `PersistResultStep` | 分页从引擎拉取预测结果，依次写入所有 `ResultSink` |
| `EngineStepSupport` | 步骤公共能力：取得并记录任务绑定的引擎实例、生成提交幂等号 |
| `ModelService` + `forecast_model` 表 | 模型注册表：登记新模型并把旧版本置为 INACTIVE、版本切换、预测时按（任务类型，预测对象，目标）选模型 |
| `ForecastResultService` + `forecast_result` 表 | 默认的结果落库实现（JDBC 批量插入）与结果分页查询 |
| `ResultSink` | 结果输出扩展点，以后要同时写业务表或推消息队列，加一个实现即可 |
| 配置 | `forecast.executor`、`forecast.transfer`、`forecast.pipelines`、`forecast.task-types`（LOAD、PRICE） |
| 测试 | `ChunkWriterTest`（2 个） |

流程编排配置（`standard` 流程）：

| 模式 | 步骤 |
|---|---|
| PREPROCESS | LOAD_DATA → PREPROCESS |
| TRAIN | LOAD_DATA → PREPROCESS → TRAIN |
| PREDICT | LOAD_DATA → PREDICT → PERSIST_RESULT |
| FULL | LOAD_DATA → PREPROCESS → TRAIN → PREDICT → PERSIST_RESULT |

**关键设计**

- **背压**。上传用信号量限制在途分片数（`transfer.parallelism`），上传慢的时候读取线程会被阻塞住，不会把整个窗口的数据都读进内存排队。配合 D5 的分段流式读取，内存占用只与“分段大小 + 在途分片数 × 分片大小”有关。
- **实例亲和**。一次任务的数据集、预处理结果、模型都在同一个引擎实例的存储里，所以任务第一次调用引擎时选定实例并写入任务记录，后续步骤固定使用它。
- **长任务不占 HTTP 连接**。预处理 / 训练 / 预测都是“提交 → 轮询”，轮询间隔从 500ms 退避到 2s；任务被取消或超时时会调用引擎的取消接口，避免引擎白白继续算。
- **步骤可跳过**。请求里直接给了预处理数据集 ID 时，取数和预处理自动跳过；预测模式下已确定模型时跳过训练。
- **线程池隔离**。传输池满了由调用线程自己执行（天然背压），任务池满了直接拒绝（过载保护）。两者互不挤占。

**验证**

- `mvn verify`：构建成功，**Java 15 个测试全部通过**（新增 `ChunkWriterTest` 2 个，验证分片行数、列式编码与 gzip）。
- 手工启动服务：日志显示 `已注册流程步骤: [LOAD_DATA, PERSIST_RESULT, PREDICT, PREPROCESS, TRAIN]，流程: [standard]`——说明五个步骤都已注册，且 `application.yml` 里的流程与任务类型配置通过了启动校验。

**遗留与明日计划**：步骤都写好了，但还没有“提交任务”的入口，所以今天没法真正跑一次流程；传输服务与各步骤的端到端验证留给明天。明天是关键的一天：实现任务提交与对外接口，把前十天的部件接成一条完整链路。

<a id="day-11"></a>
### Day 11 · 任务提交与对外接口（首次端到端）

**目标**：把前十天的部件接成一条完整链路——调用方发一个 HTTP 请求，平台完成取数、传输、预处理、训练、预测、入库，并能查到过程与结果。

**完成内容**

| 交付物 | 说明 |
|---|---|
| `ForecastTaskService` | 任务服务：提交（校验、幂等、落库、入线程池）、同步等待、取消、重试、执行与状态收尾、事件发布 |
| `TaskRequestResolver` | 请求补全与校验：时间窗口默认值、目标字段、算法、参数合并（请求覆盖任务类型默认值）、超时 |
| `RunningTaskRegistry` | 本节点的排队 / 运行中任务表，以及同步等待者 |
| `SubmitOptions` | 提交的附加信息：触发方式、定时任务 ID、来源任务、重试次数 |
| `ForecastController` | `POST /api/v1/forecast/{taskType}/{mode}?waitSeconds=`，以及任务类型与流程的查询 |
| `TaskController` | 任务详情、分页查询、**SSE 实时进度**、取消、重试、结果分页、最新结果 |
| `ModelController` | 模型列表、详情、切换当前使用的版本 |
| `TaskEventStreamService` | 监听任务事件并通过 Server-Sent Events 推送，任务结束自动关闭连接 |
| `ForecastProperties.Callback` | 回调主机白名单（提交时校验，防 SSRF；真正发回调在 D13） |
| `FakePythonEngine`（测试） | 与真实引擎协议一致的替身：校验分片 CRC 与行数、异步任务、结果分页，并可注入故障 |
| 测试 | `ForecastFlowIntegrationTest`（5 个）、`TaskRequestResolverTest`（3 个） |

**一次提交的处理过程**

1. 校验任务类型与流程存在，补全请求参数，校验回调地址；
2. 按 `requestId` 查重，已存在则直接返回原任务（`duplicated=true`）；
3. 预测模式下立即确定模型，没有可用模型就同步报错，不让任务空跑；
4. 写入 PENDING 记录（并发提交同一 `requestId` 时由唯一索引兜底）；
5. 提交到任务线程池；队列满则把任务置为失败并返回 429；
6. 执行线程把任务置为 RUNNING，创建上下文，交给 `PipelineExecutor`；
7. 根据结果或异常类型置为 SUCCESS / FAILED / CANCELLED / TIMEOUT，发布结束事件。

**关键设计**

- **默认异步，可选同步等待**。`waitSeconds=0` 立即返回 `taskId`；大于 0 时在该时间内等任务结束并直接带回详情，小规模预测用起来像同步接口，又不会无限期占着连接。
- **过载保护**。任务池是有界队列，满了就明确拒绝并返回“系统繁忙”，而不是无限堆积拖垮服务。
- **请求补全后再落库**。任务记录里存的是补全后的完整请求，重试时原样重放，结果可复现。
- **取消是协作式的**。排队中的任务直接置为取消；运行中的任务设置取消标记，执行线程在步骤边界和轮询时感知，并通知引擎取消。
- **以数据库状态为准**。执行线程收尾时如果发现任务已被别处置为终态，就采用数据库里的状态，不去覆盖。

**验证**

- `mvn verify`：构建成功，**Java 23 个测试全部通过**。集成测试用真实 Spring 上下文 + H2 演示数据 + 引擎替身，5 个场景：

| 用例 | 验证点 |
|---|---|
| `fullLoadForecastRunsAllStepsTransfersInChunksAndPersistsResults` | 一体化预测五个步骤全部成功；每片 1000 行时主数据至少分 6 片；引擎收到的行数与读出的行数一致；任务结束后临时数据集被清理；结果 2 × 96 行入库；随后可直接用训练出的模型做 PREDICT |
| `repeatedWindowIsServedFromCacheAndRequestIdIsIdempotent` | 相同时间窗口第二次不再读库；相同 `requestId` 返回同一任务 |
| `transientEngineErrorsAreRetried` | 引擎连续两次瞬时故障后重试成功 |
| `engineTaskFailureFailsTaskWithoutRetry` | 引擎任务失败时任务置为 FAILED、错误码 `ENGINE_TASK_FAILED`，且该步骤只尝试 1 次 |
| `validationErrorsReturnBusinessCodes` | 未知任务类型返回 400 / `20400`；无可用模型返回 404 / `40404` |

- **真实引擎联调**（不用替身）：同时启动 D7 的 Python 引擎和本服务，调用 `POST /api/v1/forecast/LOAD/full?waitSeconds=180`，请求体 `{"requestId":"e2e-d11","seriesKeys":["AREA_01"]}`。任务 3.9 秒完成，状态 SUCCESS：

| 步骤 | 状态 | 耗时 | 说明 |
|---|---|---|---|
| LOAD_DATA | SUCCESS | 235 ms | 上传 10840 行（负荷 8632 + 气象 2208） |
| PREPROCESS | SUCCESS | 564 ms | 规整为 8640 行，填补 8 个缺失点 |
| TRAIN | SUCCESS | 2453 ms | gbrt，验证集 MAE 10.21、RMSE 14.32、MAPE 1.73% |
| PREDICT | SUCCESS | 524 ms | 次日 96 个点 |
| PERSIST_RESULT | SUCCESS | 29 ms | 入库 96 条 |

  - `GET /api/v1/tasks/{id}/results` 返回 96 条，首条 `2026-11-18T00:00:00` 预测值 510.07，区间 [483.57, 536.57]；
  - `GET /api/v1/models` 可见新模型，状态 ACTIVE，记录了所在引擎实例；
  - 用同一个 `requestId` 再提交一次，返回同一个 `taskId` 且 `duplicated=true`；
  - 该任务的 traceId 同时出现在 Java 日志和 Python 引擎日志里，两侧可以按 traceId 串起来；
  - 两侧日志均无错误。

**遗留与明日计划**：取消、重试、SSE 推送三个接口今天没有自动化测试，也没有手工逐个验证，属于已知的覆盖缺口。里程碑“D11 首次端到端跑通”按期达成。明天做定时调度。

<a id="day-12"></a>
### Day 12 · 定时调度

**目标**：预测任务大多是周期性的（每天 6 点做日前预测）。平台要能用 Cron 自动触发，配置可在线修改，多实例部署时同一个触发点只执行一次。

**完成内容**

| 交付物 | 说明 |
|---|---|
| `forecast_job_config` 表 | 定时任务配置：Cron、时区、任务参数模板、启停、并发策略、错过补偿策略、失败重试次数与间隔、超时、上次 / 下次触发时间、乐观锁版本 |
| `forecast_job_fire_log` 表 | 触发记录，唯一键 `(job_id, fire_time, attempt)` |
| `JobConfig` / `JobFireLog` + Mapper | 两张表的实体与 Mapper |
| `JobTaskTemplate` | 参数模板：时间窗口相对“触发日期”计算，每次触发生成当次的请求 |
| `JobConfigService` | 配置的增删改查与校验（Cron 合法性、编码唯一、任务类型存在），修改后通知调度器立即刷新 |
| `ForecastJobScheduler` | 动态 Cron 调度器：启动时及每 30 秒从数据库加载配置，配置指纹变化即重新注册；计算下一次触发时间后单次调度 |
| `JobFireService` | 触发执行：集群去重、并发策略、错过补偿、失败重试；监听任务结束事件回写触发记录与任务的 `last_status` |
| `JobController` | `/api/v1/jobs`：增删改查、启用 / 停用、立即触发、触发记录、Cron 预览、调度器状态 |
| `ExecutorConfig` | 新增定时触发调度线程池（只负责准点触发，预测仍在任务线程池里执行） |
| 演示数据 | 三个演示定时任务：日前负荷（06:00）、日前量价（06:30）、负荷滚动预测（每 15 分钟，默认停用） |
| 测试 | `JobTaskTemplateTest`（3 个）；集成测试新增 `scheduledFireIsDeduplicatedAndResultWrittenBack` |

**关键设计**

- **集群去重靠数据库唯一键**。每个节点都按同一个 Cron 算出同一个触发时间点，触发前先插入触发记录，插入成功的节点执行，唯一键冲突的节点放弃。不需要引入分布式锁或选主。任务的 `requestId` 取 `JOB-{id}-{fireTime}-{attempt}`，借 D11 的幂等机制做第二道保护。
- **定时任务与接口调用走同一条链路**。触发后调用的就是 `ForecastTaskService.submit`，所以执行过程、记录、监控和接口提交的任务完全一样，没有第二套执行逻辑。
- **参数模板用相对时间**。配置里写的是“历史截至触发日 0 点、预测从触发日 +1 天开始、时长 24 小时”，而不是固定日期，每次触发自动滚动。
- **配置在线生效**。调度器比较每个任务的指纹（Cron + 时区 + 启用状态），变了才重新注册；通过接口修改的节点立即刷新，其他节点在下一个刷新周期同步。
- **策略可配**。并发策略 `SKIP`（上次未结束则跳过）/ `ALLOW`；错过补偿 `IGNORE` / `FIRE_ONCE`；失败后按 `max_retries` 与间隔自动重试。
- **结果回写用事件**。`JobFireService` 监听 D9 定义的任务结束事件来更新触发记录，执行链路不需要知道“我是被定时任务触发的”。

**验证**

- `mvn verify`：构建成功，**Java 27 个测试全部通过**。新增的集成测试模拟“两个节点在同一触发点同时触发”：第一次返回触发记录，第二次返回空；任务完成后触发记录和任务配置的 `lastStatus` 都回写为 SUCCESS，任务的触发方式为 SCHEDULE，结果 3 × 96 行。
- **真实引擎联调**：
  - 启动日志：`定时调度已启动，已注册 2 个任务，配置刷新间隔 PT30S`，两个启用的任务下次触发时间分别是次日 06:00 与 06:30（Asia/Shanghai）；
  - `GET /api/v1/jobs/cron/preview?cron=0 0 6 * * *` 返回未来 5 次触发时间；
  - `POST /api/v1/jobs/2002/trigger` 手动触发日前量价任务：5.7 秒完成，状态 SUCCESS，触发方式 MANUAL；读取量价 5760 行、负荷 17262 行、气象 4464 行，截断 48 个价格异常值，价格 MAPE 4.89%、电量 MAPE 2.16%，产出 192 条结果（price 与 volume 各 96）；
  - 触发记录状态为 SUCCESS，任务配置的 `lastStatus`、`lastTaskId` 已回写；
  - 服务日志无 ERROR。

**遗留与明日计划**

- 调度器状态接口返回的 `nextFireTime` 是服务器本地时区的时间（本机为 UTC，显示 `22:00`，即上海时间次日 06:00），与日志里带时区的写法不一致，容易看错；功能无误，记录待改进。
- 到点自动触发、错过补偿、失败重试这三条路径需要等待真实时间流逝，今天没有验证；自动化测试覆盖的是去重与回写。
- 明天做监控、可靠性与安全。

<a id="day-13"></a>
### Day 13 · 监控、可靠性与安全

**目标**：让平台“看得见、扛得住、守得住”——有业务指标和总览，节点宕机后任务不会永远卡在运行中，接口有基本的访问控制。

**完成内容**

| 交付物 | 说明 |
|---|---|
| 依赖 | Prometheus 指标注册表、springdoc（接口文档） |
| `TaskMetrics` | 监听任务事件产生业务指标：`forecast_task_submitted_total`、`forecast_task_finished_total{status}`，以及本节点运行 / 排队任务数 |
| `CallbackNotifier` | 任务结束后向请求里的 `callbackUrl` POST 任务详情，失败按退避重试，结果写入 `callback_status` |
| `TaskMaintenanceJobs` | 后台作业：定期刷新本节点活动任务的心跳；感知其他节点发起的取消；回收心跳超时的僵尸任务；节点重启时回收自己遗留的任务 |
| `MonitorController` | `GET /api/v1/monitor/overview`：各状态任务数、本节点运行情况、引擎健康、缓存命中、调度状态、近期失败 |
| `ApiKeyAuthFilter` | API Key 鉴权（`forecast.security.enabled=true` 时生效），常量时间比较；探活、指标抓取、接口文档免鉴权 |
| `OpenApiConfig` | 接口文档 `/swagger-ui.html`，声明 `X-API-Key` 安全方案 |
| `ExecutorConfig` | 新增独立的回调线程池，回调慢不会占用任务线程 |
| 配置 | Actuator 暴露 `prometheus`；`forecast.security`、springdoc 路径 |
| 测试 | 集成测试新增 `callbackIsPostedWhenTaskFinishes` |

**关键设计**

- **心跳 + 回收解决节点宕机**。活动任务每 30 秒刷新心跳；任何节点发现心跳超过 5 分钟没更新的任务，就把它置为 FAILED 并发布结束事件（定时任务据此触发重试）。这样即使执行节点被强杀，任务也会在有限时间内收敛到终态。
- **跨节点取消**。取消请求可能打到不是执行该任务的节点上，所以取消只是写库；执行节点每隔几秒查询“我正在跑的任务里哪些被请求取消了”，再在本地中止。
- **监控靠监听事件接入**。指标、回调都是 `TaskEvent` 的监听者，今天新增这些能力没有改动执行链路的任何一行。
- **回调防 SSRF**。只允许回调白名单里的主机（D11 提交时已校验），默认白名单为空，即不允许任何回调。
- **最小暴露**。免鉴权的只有健康检查、info、Prometheus 抓取和接口文档；`loggers`、`threaddump` 等运维端点同样需要 API Key。

**验证**

- `mvn verify`：构建成功，**Java 28 个测试全部通过**。新增的集成测试起一个本地 HTTP 接收端，提交带 `callbackUrl` 的任务，断言收到了包含 `taskId` 与 `"status":"SUCCESS"` 的回调。
- **真实引擎联调**（开启 `API_KEY_ENABLED=true`）：

| 检查项 | 结果 |
|---|---|
| 不带 Key 访问 `/api/v1/tasks` | HTTP 401，`{"code":10401,"message":"缺少或错误的 X-API-Key"}` |
| 带错误的 Key | HTTP 401 |
| 不带 Key 访问 `/actuator/health` | HTTP 200 |
| 取消一个已成功的任务 | 业务码 `20409`，“任务已结束，状态: SUCCESS” |
| 重试该任务 | 生成新任务，`triggerType=RETRY`、`parentTaskId` 指向原任务、`retryCount=1`，最终 SUCCESS |
| 订阅新任务的 SSE | 依次收到 `snapshot`、多条 `step_progress`（传输进度 4% → 18%）、`step_finished`、`step_started`… |
| 提交量价任务后立即取消 | 任务最终 CANCELLED，步骤轨迹为 `LOAD_DATA: SUCCESS`、`PREPROCESS: CANCELLED`；指标里引擎的 `cancel_task` 被调用 1 次 |
| `GET /api/v1/monitor/overview` | 成功 2、取消 1；已注册定时任务 2 个；缓存 20 个分段、命中率 28.6% |
| `GET /actuator/prometheus` | 可见 `forecast_task_submitted_total`、`forecast_task_finished_total`、`forecast_task_seconds_count`、`forecast_task_step_seconds_count`、`forecast_engine_calls_seconds_count`、`forecast_transfer_bytes_total`（471550）、`forecast_transfer_chunks_total`（8）等 |
| `GET /v3/api-docs` | 标题“预测任务服务 API”，共 31 个路径 |

- 服务日志无 ERROR。D11 留下的“取消、重试、SSE 没有验证”的缺口，今天通过手工联调补上了。

**遗留与明日计划**

- 联调中 `monitor/overview` 有一次把引擎显示为 DOWN（`The target server failed to respond`），而引擎当时在正常处理任务。只出现了一次，未进一步定位，推测是复用了已被对端关闭的空闲连接；业务调用有重试所以没受影响，健康探测没有重试。记录待查。
- 鉴权失败的 401 响应里 `traceId` 为空，其他错误响应都有，体验不一致，待改进。
- 僵尸任务回收需要等待 5 分钟心跳超时，今天没有实测。
- 明天做微服务化：配置中心、网关、声明式客户端。

<a id="day-14"></a>
### Day 14 · 微服务化

**目标**：让平台融入微服务体系——有统一入口，配置可集中管理和热刷新，其他服务可以像调本地方法一样调用它。

**完成内容**

| 交付物 | 说明 |
|---|---|
| `forecast-gateway` 模块 | Spring Cloud Gateway（WebFlux）+ LoadBalancer + Resilience4j + Nacos 发现 + Actuator / Prometheus / Tracing |
| 网关路由 | `/api/v1/**` → `lb://forecast-task-server`，带熔断（超时 330 秒，覆盖同步等待接口）与仅针对 GET 的重试；另有一条路由透传下游接口文档 |
| `ApiKeyGlobalFilter` / `GatewaySecurityProperties` | 网关统一 API Key 鉴权（`gateway.security.enabled=true` 时生效） |
| `AccessLogGlobalFilter` | 访问日志：为每个请求生成 `X-Request-Id` 并透传下游，记录路由、状态码、耗时 |
| 网关 `standalone` profile | 不接注册中心时用静态实例声明下游地址 |
| Nacos 配置中心 | task-server 引入 `nacos-config`，`spring.config.import: optional:nacos:…?refreshEnabled=true`，默认关闭 |
| `ForecastTaskClient` | 声明式 HTTP 客户端（Spring 6 HTTP Interface）：提交、查询任务、取消、查询结果 |
| `ForecastTaskClients` | 客户端工厂，定义了统一的返回与异常约定（见下） |
| `ForecastClientException` | 非约定错误响应时抛出，携带 HTTP 状态码、请求地址、响应体片段 |
| 测试 | `ForecastTaskClientsTest`（14 个）；集成测试新增 `declarativeClientSubmitsQueriesAndDecodesBusinessErrors` |

客户端的返回与异常约定：

| 情况 | 结果 |
|---|---|
| 调用成功 | 返回 `code == 0` 的 `R` |
| 服务端业务错误（JSON 响应体是统一结构 `R`，无论 4xx 还是 5xx） | **不抛异常**，返回 `code != 0` 的 `R` |
| 其他错误响应（网关错误页、Spring Boot 默认错误 JSON、空响应体、非 JSON） | 抛 `ForecastClientException` |
| 网络异常、注册中心无可用实例 | 抛 Spring 的 `ResourceAccessException` |

**关键设计**

- **业务错误不是异常**。“任务不存在”“队列已满”是调用方需要处理的正常分支，以 `R` 返回比让调用方到处 try-catch 更自然；真正的调用失败才抛异常。实现上用一个拦截器先缓存响应体，识别出业务错误后把状态码改写为 200 再交给解码器，原始状态码保留在响应头 `X-Forecast-Original-Status` 里。
- **提供 `isServerError(R)`**。业务错误不抛异常意味着熔断器默认不会把它算作失败；需要时可用这个方法判断 5xx 类业务错误并计入熔断。
- **客户端不依赖 Feign**。基于 Spring 自带的 HTTP Interface + `RestClient`，`forecast-api` 对 `spring-web` 只是可选依赖，调用方不会被带入额外的框架。
- **网关和服务各有一层鉴权**。经网关访问时由网关统一校验；服务被直连时由 D13 的过滤器兜底。两层可以独立开关。
- **只对 GET 重试**。提交类请求在网关层不重试，避免把一次提交变成多次（虽然有 `requestId` 幂等，但调用方不一定都带）。

**验证**

- `mvn verify`：**4 个模块全部构建成功，Java 43 个测试全部通过**。客户端的 14 个单测覆盖：请求路径与参数、API Key 头、业务错误（4xx / 5xx）以 `R` 返回、各类非约定错误抛 `ForecastClientException`、读取中断与无可用实例映射为 `ResourceAccessException`、调用方自带错误处理器时的行为、观测指标中的状态码、`isServerError`。集成测试用真实服务验证客户端的提交、查询、结果分页与业务错误解码。
- **网关联调**（网关 19000 → task-server 18080，网关开启 API Key）：

| 检查项 | 结果 |
|---|---|
| 网关健康检查 | UP |
| 经网关访问、不带 Key | HTTP 401，`{"code":10401,"message":"缺少或错误的 X-API-Key"}` |
| 经网关访问、带 Key | HTTP 200，响应头含 `X-Request-Id`（网关生成）与 `X-Trace-Id`（服务生成） |
| 经网关查询不存在的任务 | HTTP 404，业务码 `20404` 原样透传 |
| 网关访问日志 | 记录了路由 `forecast-task-server`、状态码与耗时 |

**遗留与明日计划**

- 补上了 dev 环境的回调主机白名单（`localhost`、`127.0.0.1`），这是 D11 加回调校验时漏配的。
- **Nacos 没有实测**。本机没有 Nacos，注册发现与配置热刷新只验证了“关闭时不影响启动”，开启后的行为未验证。
- 网关的熔断与重试路由配置未做故障注入验证。
- 明天收尾：镜像、Compose、生产配置、文档与全量回归。

<a id="day-15"></a>
### Day 15 · 部署与交付

**目标**：把平台变成可以交给别人部署和使用的东西——有镜像构建文件、一键编排、生产配置、告警规则和完整文档，并做一次全量回归。

**完成内容**

| 交付物 | 说明 |
|---|---|
| `forecast-task-server/Dockerfile` | 基于 `eclipse-temurin:17-jre`，非 root 用户，G1 + 按容器内存比例设堆，OOM 即退出，带 liveness 健康检查 |
| `forecast-gateway/Dockerfile` | 网关镜像，结构同上 |
| `deploy/docker-compose.yml` | 七个服务：MySQL 8.4、Redis 7.4、Nacos、Python 引擎、task-server、gateway、Prometheus；含健康检查与依赖顺序、数据卷 |
| `deploy/prometheus.yml` | 抓取 task-server 与 gateway 的 `/actuator/prometheus` |
| `deploy/alert-rules.yml` | 四条告警：任务失败率高、任务队列积压、引擎熔断器打开、任务耗时过长 |
| `application-prod.yml` | 生产配置：MySQL（批量写入重写、游标读取）、连接池、Redis、默认开启二级缓存与 API Key、默认关闭接口文档、日志落文件 |
| `README.md` | 技术栈、模块、本地快速开始、调用示例、主要接口、客户端接入与错误约定、定时任务、扩展指南、部署、测试 |
| `docs/ARCHITECTURE.md` | 总体架构图、一体化预测时序图、流程编排、多表结构、缓存、传输协议、调度、可靠性、监控、数据模型、调优参考 |

**关键设计**

- **生产与开发只差配置**。同一个 jar，`dev` profile 用内嵌 H2 和演示数据、零外部依赖；`prod` profile 接 MySQL、Redis、Nacos。差异全部在 profile 文件和环境变量里。
- **生产默认更保守**。鉴权默认开、接口文档默认关、演示数据不加载（Flyway 路径里没有 `db/demo`）。
- **告警围绕四个问题**：任务是不是在大量失败、是不是堆积了、引擎是不是不可用、是不是变慢了。指标都来自 D8、D10、D13 埋的点。

**验证：全量回归**

| 项目 | 结果 |
|---|---|
| `mvn clean verify`（4 个模块） | 成功 |
| Java 自动化测试 | **43 个全部通过**，0 失败、0 错误、0 跳过 |
| Python `pytest` | **8 个全部通过** |
| 真实引擎端到端（LOAD 一体化预测） | SUCCESS，3.8 秒，五个步骤全部成功，96 条结果入库，MAPE 1.73%（与 D11 的结果一致） |
| `docker compose config` | 语法校验通过，解析出 7 个服务 |
| Prometheus 配置、告警规则、生产配置 | YAML 解析通过，告警规则 4 条 |

Java 测试明细：

| 测试类 | 用例数 |
|---|---|
| `ForecastFlowIntegrationTest` | 8 |
| `ForecastTaskClientsTest` | 14 |
| `PipelineExecutorTest` | 5 |
| `DataSourceDefinitionTest` | 4 |
| `SegmentUnitTest` | 3 |
| `JobTaskTemplateTest` | 3 |
| `TaskRequestResolverTest` | 3 |
| `ChunkWriterTest` | 2 |
| `SegmentCodecTest` | 1 |

**未能验证的部分**

- **镜像没有构建，Compose 没有启动**。开发环境里有 Docker 客户端但没有可用的 Docker 守护进程，三个 Dockerfile 和 Compose 编排只做了语法层面的校验。
- 因此 **MySQL、Redis 二级缓存、Nacos 注册与配置热刷新、Prometheus 抓取与告警**都没有在真实组件上跑过。所有自动化测试和手工联调用的都是 `dev` / `test` 配置（H2、本地缓存、无注册中心）。
- 首次在真实环境部署时，建议按这个顺序验证：MySQL 上的 Flyway 迁移 → Redis 缓存命中与失效广播 → Nacos 注册与引擎实例发现 → 网关经注册中心路由 → Prometheus 指标与告警。

---

## 项目总结

**15 天计划全部按期完成**，四个里程碑均达成：

| 里程碑 | 计划 | 实际 |
|---|---|---|
| 工程可构建、服务可启动、任务记录可落库 | D3 | D3 |
| 三种表结构可取数并命中缓存；Python 引擎独立跑通；Java 可调用引擎 | D8 | D8 |
| 首次端到端跑通一体化预测 | D11 | D11 |
| 监控、安全、网关、客户端、部署、文档齐备，全量回归通过 | D15 | D15 |

**计划执行中的调整**

- D6：引擎协议文档改到 D7 与算法流程一起写。
- D8：Nacos 服务发现提前到 D8 引入（引擎实例发现需要它），D14 只剩配置中心与网关。
- D11：SSE 实时进度提前到 D11（任务接口直接依赖它）。
- D14：补上 D11 漏配的 dev 环境回调白名单。

**遗留问题清单**（按优先级）

| 问题 | 发现于 | 影响 |
|---|---|---|
| 生产组件（MySQL / Redis / Nacos / Prometheus / Docker 镜像）未实测 | D15 | 首次部署前必须验证 |
| 到点自动触发、错过补偿、定时任务失败重试未实测 | D12 | 需要在运行环境观察一个触发周期 |
| 僵尸任务回收（5 分钟心跳超时）未实测 | D13 | 需要做一次强杀节点的演练 |
| 引擎健康探测偶发一次误报 DOWN，未定位 | D13 | 可能触发误告警 |
| 网关熔断与重试未做故障注入验证 | D14 | – |
| 调度状态接口的 `nextFireTime` 用服务器本地时区显示 | D12 | 容易看错时间，功能无误 |
| 鉴权失败的 401 响应没有 `traceId` | D13 | 排查体验不一致 |
| 演示数据生成完成前的查询会缓存空分段 | D5 | 仅影响开发环境刚启动的几秒 |
