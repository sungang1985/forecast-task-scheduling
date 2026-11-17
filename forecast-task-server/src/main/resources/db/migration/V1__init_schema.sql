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
