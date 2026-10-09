# forecast-python-engine

Python 预测引擎（FastAPI + pandas + scikit-learn），由 Java 预测任务服务通过 HTTP 调用。
仓库中的实现是**可运行的参考实现**：协议完整、流程完整，算法可按业务替换（见“扩展”）。

## 运行

```bash
pip install -r requirements-dev.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000      # 启动
pytest -q tests                                        # 测试
```

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `ENGINE_DATA_DIR` | `./engine_data` | 数据目录；多实例部署须挂载共享存储 |
| `ENGINE_WORKERS` | `4` | 异步任务线程数 |
| `ENGINE_TEMP_TTL_HOURS` | `48` | 数据集/预处理结果/预测结果/任务状态保留时长 |
| `ENGINE_MODEL_TTL_DAYS` | `0` | 模型保留天数，0 为永久 |
| `ENGINE_MAX_CHUNK_BYTES` | `67108864` | 单分片上限 |
| `NACOS_ADDR` | 空 | 设置后自动注册到 Nacos（服务名 `forecast-python-engine`） |
| `ENGINE_ADVERTISE_IP` / `ENGINE_PORT` | 自动 / `8000` | 注册到 Nacos 的地址 |

## 接口协议

所有 JSON 字段为 snake_case；时间为 ISO-8601 本地时间（无时区）。Java 侧透传 `X-Trace-Id`、`X-Task-Id`、`traceparent`，引擎日志中输出以便关联。

### 1. 数据集分批上传

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/datasets` | 创建会话，声明分区：`primary`（预测目标）+ 若干 `exogenous`（外生变量），返回 `dataset_id` |
| PUT | `/api/v1/datasets/{id}/parts/{part}/chunks/{seq}` | 上传分片（幂等）。Body 为 `{"columns":[...],"rows":[[...]]}`，头：`X-Payload-Encoding: gzip`、`X-Chunk-Rows`、`X-Chunk-Checksum`（CRC32 十六进制） |
| POST | `/api/v1/datasets/{id}/complete` | `{"parts":{"primary":{"chunks":N,"rows":M}}}`；缺片时返回 `missing_chunks`，否则合并并校验行数 |
| DELETE | `/api/v1/datasets/{id}` | 删除数据集 |

分区列固定以 `ts, series_key` 开头，后接统一字段名（如 `load`、`price`、`temperature`）。

### 2. 异步任务（提交即返回 `task_id`，轮询状态）

| 方法 | 路径 | 请求要点 | 成功结果 `result` |
|---|---|---|---|
| POST | `/api/v1/preprocess` | `dataset_id, targets, frequency, params` | `processed_dataset_id, rows, series, stats` |
| POST | `/api/v1/train` | `processed_dataset_id, targets, algorithm, params` | `model_id, train_rows, metrics{overall,series}` |
| POST | `/api/v1/predict` | `model_id, dataset_id, forecast_start, forecast_end` | `prediction_id, rows, series` |
| GET | `/api/v1/tasks/{task_id}` | — | `status`(PENDING/RUNNING/SUCCESS/FAILED/CANCELLED), `progress`, `message`, `result`, `error` |
| POST | `/api/v1/tasks/{task_id}/cancel` | — | 协作式取消 |

提交请求带 `request_id` 时按其去重，调用方可安全重试。

### 3. 结果与其他

- `GET /api/v1/predictions/{id}?offset=&limit=`：分页拉取预测结果 `series_key, target, ts, value, lower, upper`
- `GET /api/v1/models/{id}`：模型元数据与评估指标
- `GET /api/v1/algorithms`、`GET /health`

## 处理流程

- **预处理**：去重 → 规整到目标频率的时间网格 → 负值剔除（负荷）→ 异常值截断（负荷 IQR、价格分位数）→ 缺失值填补（短缺口插值、长缺口同时段回填）→ 外生变量重采样（如逐小时气象插值到 15 分钟）并按 `(series_key, ts)` 或 `ts` 对齐。
- **特征**：日历特征（时段、星期、月份、年内周期）+ 目标滞后（默认 1 天、7 天）+ 外生变量。
- **训练**：每个 (预测对象, 目标) 一个模型；按时间顺序切分 15% 验证集评估 MAE/RMSE/MAPE，再全量重训；残差标准差用于 95% 区间。
- **预测**：对近期历史做相同预处理，以最短滞后为步长分块递推（未来时段的滞后特征使用已预测值），支持历史末尾与预测起点之间存在空档。

## 扩展

- 新算法：在 `app/pipeline/algorithms.py` 中 `@register("名称")` 注册工厂函数，Java 请求 `algorithm` 指定即可。
- 新任务类型的预处理参数：在 `app/pipeline/preprocess.py` 的 `PREPROCESS_DEFAULTS` 中配置。
- 多实例部署：挂载共享 `ENGINE_DATA_DIR`；Java 侧会把一次任务的所有调用固定到同一实例。
