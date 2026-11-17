-- =====================================================================
-- 演示用源数据表（仅 dev 环境加载）：展示三种不同表结构的预训练数据
--   1. ods_load_curve_96   宽表：每行一天 96 点负荷（电力行业常见格式）
--   2. ods_market_price    日期 + 时段序号：日前/实时出清价格与出清电量
--   3. ods_weather_hourly  长表：逐小时气象数据（含未来预报）
-- 可重复迁移，脚本需保持幂等
-- =====================================================================

CREATE TABLE IF NOT EXISTS ods_load_curve_96
(
    area_code VARCHAR(32) NOT NULL COMMENT '区域编码',
    load_date DATE        NOT NULL COMMENT '日期',
    p001 DECIMAL(12, 3) NULL,
    p002 DECIMAL(12, 3) NULL,
    p003 DECIMAL(12, 3) NULL,
    p004 DECIMAL(12, 3) NULL,
    p005 DECIMAL(12, 3) NULL,
    p006 DECIMAL(12, 3) NULL,
    p007 DECIMAL(12, 3) NULL,
    p008 DECIMAL(12, 3) NULL,
    p009 DECIMAL(12, 3) NULL,
    p010 DECIMAL(12, 3) NULL,
    p011 DECIMAL(12, 3) NULL,
    p012 DECIMAL(12, 3) NULL,
    p013 DECIMAL(12, 3) NULL,
    p014 DECIMAL(12, 3) NULL,
    p015 DECIMAL(12, 3) NULL,
    p016 DECIMAL(12, 3) NULL,
    p017 DECIMAL(12, 3) NULL,
    p018 DECIMAL(12, 3) NULL,
    p019 DECIMAL(12, 3) NULL,
    p020 DECIMAL(12, 3) NULL,
    p021 DECIMAL(12, 3) NULL,
    p022 DECIMAL(12, 3) NULL,
    p023 DECIMAL(12, 3) NULL,
    p024 DECIMAL(12, 3) NULL,
    p025 DECIMAL(12, 3) NULL,
    p026 DECIMAL(12, 3) NULL,
    p027 DECIMAL(12, 3) NULL,
    p028 DECIMAL(12, 3) NULL,
    p029 DECIMAL(12, 3) NULL,
    p030 DECIMAL(12, 3) NULL,
    p031 DECIMAL(12, 3) NULL,
    p032 DECIMAL(12, 3) NULL,
    p033 DECIMAL(12, 3) NULL,
    p034 DECIMAL(12, 3) NULL,
    p035 DECIMAL(12, 3) NULL,
    p036 DECIMAL(12, 3) NULL,
    p037 DECIMAL(12, 3) NULL,
    p038 DECIMAL(12, 3) NULL,
    p039 DECIMAL(12, 3) NULL,
    p040 DECIMAL(12, 3) NULL,
    p041 DECIMAL(12, 3) NULL,
    p042 DECIMAL(12, 3) NULL,
    p043 DECIMAL(12, 3) NULL,
    p044 DECIMAL(12, 3) NULL,
    p045 DECIMAL(12, 3) NULL,
    p046 DECIMAL(12, 3) NULL,
    p047 DECIMAL(12, 3) NULL,
    p048 DECIMAL(12, 3) NULL,
    p049 DECIMAL(12, 3) NULL,
    p050 DECIMAL(12, 3) NULL,
    p051 DECIMAL(12, 3) NULL,
    p052 DECIMAL(12, 3) NULL,
    p053 DECIMAL(12, 3) NULL,
    p054 DECIMAL(12, 3) NULL,
    p055 DECIMAL(12, 3) NULL,
    p056 DECIMAL(12, 3) NULL,
    p057 DECIMAL(12, 3) NULL,
    p058 DECIMAL(12, 3) NULL,
    p059 DECIMAL(12, 3) NULL,
    p060 DECIMAL(12, 3) NULL,
    p061 DECIMAL(12, 3) NULL,
    p062 DECIMAL(12, 3) NULL,
    p063 DECIMAL(12, 3) NULL,
    p064 DECIMAL(12, 3) NULL,
    p065 DECIMAL(12, 3) NULL,
    p066 DECIMAL(12, 3) NULL,
    p067 DECIMAL(12, 3) NULL,
    p068 DECIMAL(12, 3) NULL,
    p069 DECIMAL(12, 3) NULL,
    p070 DECIMAL(12, 3) NULL,
    p071 DECIMAL(12, 3) NULL,
    p072 DECIMAL(12, 3) NULL,
    p073 DECIMAL(12, 3) NULL,
    p074 DECIMAL(12, 3) NULL,
    p075 DECIMAL(12, 3) NULL,
    p076 DECIMAL(12, 3) NULL,
    p077 DECIMAL(12, 3) NULL,
    p078 DECIMAL(12, 3) NULL,
    p079 DECIMAL(12, 3) NULL,
    p080 DECIMAL(12, 3) NULL,
    p081 DECIMAL(12, 3) NULL,
    p082 DECIMAL(12, 3) NULL,
    p083 DECIMAL(12, 3) NULL,
    p084 DECIMAL(12, 3) NULL,
    p085 DECIMAL(12, 3) NULL,
    p086 DECIMAL(12, 3) NULL,
    p087 DECIMAL(12, 3) NULL,
    p088 DECIMAL(12, 3) NULL,
    p089 DECIMAL(12, 3) NULL,
    p090 DECIMAL(12, 3) NULL,
    p091 DECIMAL(12, 3) NULL,
    p092 DECIMAL(12, 3) NULL,
    p093 DECIMAL(12, 3) NULL,
    p094 DECIMAL(12, 3) NULL,
    p095 DECIMAL(12, 3) NULL,
    p096 DECIMAL(12, 3) NULL,
    PRIMARY KEY (area_code, load_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='96点负荷曲线（宽表）';

CREATE TABLE IF NOT EXISTS ods_market_price
(
    market_code     VARCHAR(32)    NOT NULL COMMENT '市场/节点编码',
    trade_date      DATE           NOT NULL COMMENT '交易日',
    period_no       INT            NOT NULL COMMENT '时段序号 1..96',
    price_type      VARCHAR(8)     NOT NULL COMMENT 'DA 日前 / RT 实时',
    clearing_price  DECIMAL(12, 3) NULL COMMENT '出清价格 元/MWh',
    clearing_volume DECIMAL(14, 3) NULL COMMENT '出清电量 MWh',
    PRIMARY KEY (market_code, trade_date, period_no, price_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='电力市场出清量价（日期+时段）';

CREATE TABLE IF NOT EXISTS ods_weather_hourly
(
    area_code   VARCHAR(32)   NOT NULL COMMENT '区域编码',
    record_time DATETIME      NOT NULL COMMENT '时间',
    temperature DECIMAL(6, 2) NULL COMMENT '气温 ℃',
    humidity    DECIMAL(6, 2) NULL COMMENT '相对湿度 %',
    PRIMARY KEY (area_code, record_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='逐小时气象（长表）';

-- 数据源元数据：把三种结构统一映射为 (series_key, ts, fields...)
INSERT INTO forecast_data_source (id, source_code, source_name, table_name, layout, key_column, time_column,
                                  date_column, period_column, points_per_day, field_mapping, fixed_filters,
                                  segment_unit, cache_enabled, enabled, remark, created_at, updated_at)
SELECT 1001, 'load_curve_96', '96点负荷曲线', 'ods_load_curve_96', 'WIDE_POINTS', 'area_code', NULL,
       'load_date', NULL, 96, '{"load":"p%03d"}', NULL, 'MONTH', 1, 1, '宽表：p001..p096', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM forecast_data_source WHERE source_code = 'load_curve_96');

INSERT INTO forecast_data_source (id, source_code, source_name, table_name, layout, key_column, time_column,
                                  date_column, period_column, points_per_day, field_mapping, fixed_filters,
                                  segment_unit, cache_enabled, enabled, remark, created_at, updated_at)
SELECT 1002, 'market_price_da', '日前出清量价', 'ods_market_price', 'DATE_PERIOD', 'market_code', NULL,
       'trade_date', 'period_no', 96, '{"price":"clearing_price","volume":"clearing_volume"}',
       '{"price_type":"DA"}', 'MONTH', 1, 1, '日期+时段序号，固定过滤日前价格', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM forecast_data_source WHERE source_code = 'market_price_da');

INSERT INTO forecast_data_source (id, source_code, source_name, table_name, layout, key_column, time_column,
                                  date_column, period_column, points_per_day, field_mapping, fixed_filters,
                                  segment_unit, cache_enabled, enabled, remark, created_at, updated_at)
SELECT 1003, 'weather_hourly', '逐小时气象', 'ods_weather_hourly', 'LONG_TIME', 'area_code', 'record_time',
       NULL, NULL, NULL, '{"temperature":"temperature","humidity":"humidity"}', NULL, 'MONTH', 1, 1,
       '长表：时间列 + 指标列', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM forecast_data_source WHERE source_code = 'weather_hourly');

-- 演示定时任务：每天 06:00 负荷一体化预测、06:30 量价一体化预测
INSERT INTO forecast_job_config (id, job_code, job_name, task_type, mode, cron_expression, time_zone, task_params,
                                 enabled, concurrent_policy, misfire_policy, max_retries, retry_interval_seconds,
                                 timeout_seconds, description, version, created_at, updated_at)
SELECT 2001, 'LOAD_DAY_AHEAD', '日前负荷一体化预测', 'LOAD', 'FULL', '0 0 6 * * *', 'Asia/Shanghai',
       '{"seriesKeys":["AREA_01","AREA_02","AREA_03"],"historyDays":60,"historyEndOffsetDays":0,"forecastStartOffsetDays":1,"horizonHours":24}',
       1, 'SKIP', 'FIRE_ONCE', 2, 300, 3600, '每天 6 点预测次日 96 点负荷', 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM forecast_job_config WHERE job_code = 'LOAD_DAY_AHEAD');

INSERT INTO forecast_job_config (id, job_code, job_name, task_type, mode, cron_expression, time_zone, task_params,
                                 enabled, concurrent_policy, misfire_policy, max_retries, retry_interval_seconds,
                                 timeout_seconds, description, version, created_at, updated_at)
SELECT 2002, 'PRICE_DAY_AHEAD', '日前量价一体化预测', 'PRICE', 'FULL', '0 30 6 * * *', 'Asia/Shanghai',
       '{"seriesKeys":["MARKET_A"],"historyDays":60,"historyEndOffsetDays":0,"forecastStartOffsetDays":1,"horizonHours":24}',
       1, 'SKIP', 'IGNORE', 1, 300, 3600, '每天 6:30 预测次日 96 时段出清价格与电量', 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM forecast_job_config WHERE job_code = 'PRICE_DAY_AHEAD');

INSERT INTO forecast_job_config (id, job_code, job_name, task_type, mode, cron_expression, time_zone, task_params,
                                 enabled, concurrent_policy, misfire_policy, max_retries, retry_interval_seconds,
                                 timeout_seconds, description, version, created_at, updated_at)
SELECT 2003, 'LOAD_ROLLING_PREDICT', '负荷滚动预测（演示，默认停用）', 'LOAD', 'PREDICT', '0 */15 * * * *', 'Asia/Shanghai',
       '{"seriesKeys":["AREA_01"],"historyDays":14,"historyEndOffsetDays":1,"forecastStartOffsetDays":0,"horizonHours":24}',
       0, 'SKIP', 'IGNORE', 0, 60, 600, '每 15 分钟使用最新模型滚动预测', 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM forecast_job_config WHERE job_code = 'LOAD_ROLLING_PREDICT');
