# 开发日志（每日进度与详情）

> 计划见 [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)。本文每天随代码一起提交：上半部分是进度总览，下半部分是每天的开发详情。
> 每天对应一个提交，提交标题以 `Day NN:` 开头，可用 `git log --oneline` 对照。

## 进度总览

**当前进度：8 / 15 天（53%）**

`████████░░░░░░░`

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
| D9 | 三、编排与调度 | 流程编排框架 | ⬜ 未开始 | – | – | – | – |
| D10 | 三、编排与调度 | 分批传输与内置步骤 | ⬜ 未开始 | – | – | – | – |
| D11 | 三、编排与调度 | 任务提交与对外接口 | ⬜ 未开始 | – | – | – | – |
| D12 | 三、编排与调度 | 定时调度 | ⬜ 未开始 | – | – | – | – |
| D13 | 四、可观测与交付 | 监控、可靠性与安全 | ⬜ 未开始 | – | – | – | – |
| D14 | 四、可观测与交付 | 微服务化 | ⬜ 未开始 | – | – | – | – |
| D15 | 四、可观测与交付 | 部署与交付 | ⬜ 未开始 | – | – | – | – |

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
