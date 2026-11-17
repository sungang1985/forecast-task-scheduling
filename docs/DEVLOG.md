# 开发日志（每日进度与详情）

> 计划见 [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)。本文每天随代码一起提交：上半部分是进度总览，下半部分是每天的开发详情。
> 每天对应一个提交，提交标题以 `Day NN:` 开头，可用 `git log --oneline` 对照。

## 进度总览

**当前进度：4 / 15 天（27%）**

`████░░░░░░░░░░░`

| 天 | 阶段 | 主题 | 状态 | 当天新增文件 | 当天新增代码行 | 累计代码行 | 自动化测试（累计通过） |
|---|---|---|---|---|---|---|---|
| [D1](#day-01) | 一、基础底座 | 工程骨架与公共模块 | ✅ 完成 | 14 | +515 | 515 | –（仅构建） |
| [D2](#day-02) | 一、基础底座 | 接口契约与服务骨架 | ✅ 完成 | 14 | +630 | 1145 | –（构建 + 手工冒烟） |
| [D3](#day-03) | 一、基础底座 | 持久化与任务记录 | ✅ 完成 | 9 | +634 / −1 | 1778 | –（构建 + 手工冒烟） |
| [D4](#day-04) | 二、数据与引擎 | 多表结构数据源 | ✅ 完成 | 18 | +1291 / −1 | 3068 | 7 |
| D5 | 二、数据与引擎 | 分段缓存与取数服务 | ⬜ 未开始 | – | – | – | – |
| D6 | 二、数据与引擎 | Python 引擎（上）：协议与存储 | ⬜ 未开始 | – | – | – | – |
| D7 | 二、数据与引擎 | Python 引擎（下）：算法流程 | ⬜ 未开始 | – | – | – | – |
| D8 | 二、数据与引擎 | Java 引擎客户端 | ⬜ 未开始 | – | – | – | – |
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
