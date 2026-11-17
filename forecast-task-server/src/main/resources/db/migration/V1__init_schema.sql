-- =====================================================================
-- 预测任务平台核心表（MySQL 8，兼容 H2 MySQL 模式）
-- =====================================================================

-- 预测任务实例：每次接口调用 / 定时触发对应一条记录，用于状态跟踪与链路追踪
CREATE TABLE IF NOT EXISTS forecast_task_instance
(
    id               BIGINT        NOT NULL COMMENT '主键',
    task_id          VARCHAR(40)   NOT NULL COMMENT '任务ID',
    task_type        VARCHAR(32)   NOT NULL COMMENT '任务类型 LOAD/PRICE...',
    mode             VARCHAR(16)   NOT NULL COMMENT '执行模式 PREPROCESS/TRAIN/PREDICT/FULL',
    status           VARCHAR(16)   NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/CANCELLED/TIMEOUT',
    progress         INT           NOT NULL DEFAULT 0 COMMENT '进度 0-100',
    current_step     VARCHAR(64)   NULL COMMENT '当前步骤',
    trigger_type     VARCHAR(16)   NOT NULL COMMENT 'API/SCHEDULE/MANUAL/RETRY',
    job_id           BIGINT        NULL COMMENT '定时任务ID',
    job_fire_time    DATETIME(3)   NULL COMMENT '定时任务计划触发时间',
    request_id       VARCHAR(64)   NULL COMMENT '幂等请求号',
    parent_task_id   VARCHAR(40)   NULL COMMENT '重试来源任务',
    retry_count      INT           NOT NULL DEFAULT 0 COMMENT '重试次数',
    trace_id         VARCHAR(64)   NULL COMMENT '链路ID',
    executor_node    VARCHAR(128)  NULL COMMENT '执行节点',
    engine_endpoint  VARCHAR(255)  NULL COMMENT '绑定的 Python 引擎实例',
    model_id         VARCHAR(64)   NULL COMMENT '使用/产出的模型',
    request_json     TEXT          NULL COMMENT '解析后的请求参数',
    result_json      TEXT          NULL COMMENT '结果摘要',
    error_code       VARCHAR(32)   NULL,
    error_message    VARCHAR(2000) NULL,
    cancel_requested TINYINT       NOT NULL DEFAULT 0 COMMENT '是否请求取消',
    callback_url     VARCHAR(512)  NULL,
    callback_status  VARCHAR(16)   NULL COMMENT 'SUCCESS/FAILED/SKIPPED',
    timeout_seconds  INT           NULL,
    submit_time      DATETIME(3)   NOT NULL,
    start_time       DATETIME(3)   NULL,
    end_time         DATETIME(3)   NULL,
    duration_ms      BIGINT        NULL,
    heartbeat_time   DATETIME(3)   NULL COMMENT '执行心跳',
    created_at       DATETIME(3)   NOT NULL,
    updated_at       DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_instance_task_id (task_id),
    UNIQUE KEY uk_task_instance_request_id (request_id),
    KEY idx_task_instance_status (status, heartbeat_time),
    KEY idx_task_instance_type_time (task_type, submit_time),
    KEY idx_task_instance_job (job_id, submit_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='预测任务实例';

-- 流程步骤执行轨迹
CREATE TABLE IF NOT EXISTS forecast_task_step
(
    id             BIGINT        NOT NULL,
    task_id        VARCHAR(40)   NOT NULL,
    step_order     INT           NOT NULL,
    step_name      VARCHAR(64)   NOT NULL,
    status         VARCHAR(16)   NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED/SKIPPED/CANCELLED',
    attempt        INT           NOT NULL DEFAULT 1,
    engine_task_id VARCHAR(64)   NULL COMMENT 'Python 引擎侧任务ID',
    message        VARCHAR(2000) NULL,
    output_json    TEXT          NULL,
    start_time     DATETIME(3)   NOT NULL,
    end_time       DATETIME(3)   NULL,
    duration_ms    BIGINT        NULL,
    created_at     DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_task_step_task (task_id, step_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='预测任务步骤';

-- 预测结果
CREATE TABLE IF NOT EXISTS forecast_result
(
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    task_id        VARCHAR(40)    NOT NULL,
    task_type      VARCHAR(32)    NOT NULL,
    job_id         BIGINT         NULL,
    series_key     VARCHAR(64)    NOT NULL COMMENT '预测对象',
    target_field   VARCHAR(64)    NOT NULL COMMENT '预测目标（load/price/volume...）',
    forecast_time  DATETIME       NOT NULL,
    forecast_value DECIMAL(18, 4) NULL,
    lower_bound    DECIMAL(18, 4) NULL,
    upper_bound    DECIMAL(18, 4) NULL,
    model_id       VARCHAR(64)    NULL,
    created_at     DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_result_task (task_id),
    KEY idx_result_series_time (task_type, series_key, target_field, forecast_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='预测结果';

-- 模型注册表
CREATE TABLE IF NOT EXISTS forecast_model
(
    id             BIGINT        NOT NULL,
    model_id       VARCHAR(64)   NOT NULL,
    task_type      VARCHAR(32)   NOT NULL,
    algorithm      VARCHAR(32)   NOT NULL,
    series_keys    VARCHAR(4000) NULL COMMENT '训练覆盖的预测对象，逗号分隔，* 表示全部',
    target_fields  VARCHAR(255)  NULL,
    train_start    DATETIME      NULL,
    train_end      DATETIME      NULL,
    train_rows     BIGINT        NULL,
    metrics_json   TEXT          NULL,
    params_json    TEXT          NULL,
    status         VARCHAR(16)   NOT NULL COMMENT 'ACTIVE/INACTIVE',
    source_task_id VARCHAR(40)   NULL,
    engine_endpoint VARCHAR(255) NULL,
    created_at     DATETIME(3)   NOT NULL,
    updated_at     DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_model_id (model_id),
    KEY idx_model_type_status (task_type, status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='模型注册表';

-- 定时任务配置
CREATE TABLE IF NOT EXISTS forecast_job_config
(
    id                     BIGINT        NOT NULL,
    job_code               VARCHAR(64)   NOT NULL,
    job_name               VARCHAR(128)  NOT NULL,
    task_type              VARCHAR(32)   NOT NULL,
    mode                   VARCHAR(16)   NOT NULL,
    cron_expression        VARCHAR(64)   NOT NULL COMMENT 'Spring 6 位 Cron：秒 分 时 日 月 周',
    time_zone              VARCHAR(64)   NULL,
    task_params            TEXT          NULL COMMENT '任务参数模板(JSON)，时间窗口相对触发时间计算',
    enabled                TINYINT       NOT NULL DEFAULT 1,
    concurrent_policy      VARCHAR(16)   NOT NULL DEFAULT 'SKIP' COMMENT 'SKIP:上次未结束则跳过 / ALLOW',
    misfire_policy         VARCHAR(16)   NOT NULL DEFAULT 'IGNORE' COMMENT 'IGNORE / FIRE_ONCE',
    max_retries            INT           NOT NULL DEFAULT 0,
    retry_interval_seconds INT           NOT NULL DEFAULT 300,
    timeout_seconds        INT           NULL,
    description            VARCHAR(512)  NULL,
    last_fire_time         DATETIME(3)   NULL,
    next_fire_time         DATETIME(3)   NULL,
    last_task_id           VARCHAR(40)   NULL,
    last_status            VARCHAR(16)   NULL,
    version                INT           NOT NULL DEFAULT 0,
    created_at             DATETIME(3)   NOT NULL,
    updated_at             DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_config_code (job_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='定时任务配置';

-- 定时任务触发记录：(job_id, fire_time, attempt) 唯一，保证集群多实例下同一触发点只执行一次
CREATE TABLE IF NOT EXISTS forecast_job_fire_log
(
    id               BIGINT        NOT NULL,
    job_id           BIGINT        NOT NULL,
    fire_time        DATETIME(3)   NOT NULL COMMENT '计划触发时间',
    attempt          INT           NOT NULL DEFAULT 0 COMMENT '0 为首次，>0 为失败重试',
    trigger_type     VARCHAR(16)   NOT NULL,
    actual_fire_time DATETIME(3)   NOT NULL,
    node             VARCHAR(128)  NULL,
    task_id          VARCHAR(40)   NULL,
    status           VARCHAR(16)   NOT NULL COMMENT 'TRIGGERED/SKIPPED/SUCCESS/FAILED/...',
    message          VARCHAR(2000) NULL,
    created_at       DATETIME(3)   NOT NULL,
    updated_at       DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_fire (job_id, fire_time, attempt),
    KEY idx_job_fire_task (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='定时任务触发记录';

-- 预训练数据源元数据：描述不同表结构如何映射为统一的 (series_key, ts, fields...) 时序数据
CREATE TABLE IF NOT EXISTS forecast_data_source
(
    id                   BIGINT        NOT NULL,
    source_code          VARCHAR(64)   NOT NULL,
    source_name          VARCHAR(128)  NOT NULL,
    table_name           VARCHAR(128)  NOT NULL,
    layout               VARCHAR(16)   NOT NULL COMMENT 'LONG_TIME/DATE_PERIOD/WIDE_POINTS',
    key_column           VARCHAR(64)   NULL COMMENT '预测对象列，为空表示单序列',
    time_column          VARCHAR(64)   NULL COMMENT 'LONG_TIME 布局的时间列',
    date_column          VARCHAR(64)   NULL COMMENT 'DATE_PERIOD/WIDE_POINTS 布局的日期列',
    period_column        VARCHAR(64)   NULL COMMENT 'DATE_PERIOD 布局的时段序号列(1..N)',
    points_per_day       INT           NULL COMMENT '每日点数 24/48/96/288',
    field_mapping        VARCHAR(2000) NOT NULL COMMENT '字段映射 JSON：统一字段 -> 列名（WIDE_POINTS 为列名格式，如 p%03d）',
    fixed_filters        VARCHAR(2000) NULL COMMENT '固定过滤条件 JSON：列名 -> 值',
    segment_unit         VARCHAR(8)    NOT NULL DEFAULT 'MONTH' COMMENT '缓存分段粒度 DAY/MONTH',
    cache_enabled        TINYINT       NOT NULL DEFAULT 1,
    enabled              TINYINT       NOT NULL DEFAULT 1,
    remark               VARCHAR(512)  NULL,
    created_at           DATETIME(3)   NOT NULL,
    updated_at           DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_data_source_code (source_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='预训练数据源配置';
