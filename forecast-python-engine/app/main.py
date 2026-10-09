"""Python 预测引擎（FastAPI）。

接口分三类：
1. 数据集分批上传：创建会话 -> 幂等上传分片（gzip + CRC32 + 行数校验）-> 完成合并（缺片检测）；
2. 异步任务：预处理 / 训练 / 预测，提交即返回 task_id，调用方轮询状态，可取消；
3. 预测结果分页拉取、健康检查。
"""
from __future__ import annotations

import gzip
import hmac
import json
import logging
import threading
import time
import zlib
from contextlib import asynccontextmanager

import pandas as pd
from fastapi import FastAPI, Header, Query, Request, Response
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse

from . import registry, storage
from .config import settings
from .logging_context import setup_logging, task_id_var, trace_id_var
from .pipeline import algorithms
from .pipeline.predict import run_predict
from .pipeline.preprocess import run_preprocess
from .pipeline.train import run_train
from .schemas import (CompleteDatasetRequest, CompleteDatasetResponse, CreateDatasetRequest,
                      CreateDatasetResponse, EngineHealth, EngineTaskRef, EngineTaskStatus, PredictionPage,
                      PredictionRow, PredictRequest, PreprocessRequest, TrainRequest)
from .tasks import task_manager

setup_logging()
log = logging.getLogger("forecast.engine")
_stop_event = threading.Event()


def _housekeeping() -> None:
    while not _stop_event.wait(600):
        try:
            removed = storage.cleanup_expired()
            task_manager.evict_finished()
            if removed:
                log.info("清理过期临时数据 %d 项", removed)
        except Exception as e:  # noqa: BLE001
            log.warning("清理任务异常: %s", e)


@asynccontextmanager
async def lifespan(_: FastAPI):
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    threading.Thread(target=_housekeeping, name="housekeeping", daemon=True).start()
    registry.register()
    log.info("Python 预测引擎启动 version=%s workers=%d dataDir=%s algorithms=%s", settings.version,
             settings.workers, settings.data_dir, sorted(algorithms.ALGORITHMS))
    yield
    _stop_event.set()
    registry.deregister()
    task_manager.shutdown()


app = FastAPI(title="Forecast Python Engine", version=settings.version, lifespan=lifespan)


@app.middleware("http")
async def engine_auth(request: Request, call_next):
    """可选的服务间令牌校验（引擎应只在内网暴露，令牌作为纵深防御）。"""
    if settings.token and request.url.path != "/health":
        provided = request.headers.get("x-engine-token", "")
        if not hmac.compare_digest(provided.encode(), settings.token.encode()):
            return JSONResponse(status_code=401, content={"detail": "invalid engine token"})
    return await call_next(request)


@app.middleware("http")
async def trace_context(request: Request, call_next):
    """读取 Java 侧透传的链路信息写入日志上下文。"""
    trace_id = request.headers.get("x-trace-id")
    if not trace_id:
        traceparent = request.headers.get("traceparent", "")
        parts = traceparent.split("-")
        trace_id = parts[1] if len(parts) >= 2 else "-"
    trace_token = trace_id_var.set(trace_id)
    task_token = task_id_var.set(request.headers.get("x-task-id", "-"))
    begin = time.time()
    try:
        response = await call_next(request)
        if request.url.path != "/health":
            log.debug("%s %s -> %d %.1fms", request.method, request.url.path, response.status_code,
                      (time.time() - begin) * 1000)
        return response
    finally:
        trace_id_var.reset(trace_token)
        task_id_var.reset(task_token)


@app.exception_handler(storage.NotFoundError)
async def not_found(_: Request, exc: storage.NotFoundError):
    return JSONResponse(status_code=404, content={"detail": str(exc)})


@app.exception_handler(storage.ValidationError)
async def invalid(_: Request, exc: storage.ValidationError):
    return JSONResponse(status_code=400, content={"detail": str(exc)})


# ------------------------------------------------------------------ health

@app.get("/health", response_model=EngineHealth)
def health() -> EngineHealth:
    stats = task_manager.stats()
    return EngineHealth(status="UP", version=settings.version, workers=task_manager.workers,
                        running_tasks=stats["running"], queued_tasks=stats["queued"])


# ------------------------------------------------------------------ datasets

@app.post("/api/v1/datasets", response_model=CreateDatasetResponse)
def create_dataset(req: CreateDatasetRequest) -> CreateDatasetResponse:
    if "primary" not in req.parts:
        raise storage.ValidationError("parts 必须包含 primary 分区")
    for name, spec in req.parts.items():
        storage.check_part(name)
        if spec.columns[:2] != ["ts", "series_key"]:
            raise storage.ValidationError(f"分区 {name} 的前两列必须为 ts, series_key")
    dataset_id = storage.create_dataset({
        "task_id": req.task_id, "task_type": req.task_type, "frequency": req.frequency,
        "parts": {k: v.model_dump() for k, v in req.parts.items()},
    })
    log.info("创建数据集 %s parts=%s", dataset_id, list(req.parts))
    return CreateDatasetResponse(dataset_id=dataset_id)


def _decode_chunk(body: bytes, encoding: str) -> dict:
    raw = gzip.decompress(body) if encoding == "gzip" else body
    return json.loads(raw)


@app.put("/api/v1/datasets/{dataset_id}/parts/{part}/chunks/{seq}", status_code=204)
async def upload_chunk(dataset_id: str, part: str, seq: int, request: Request,
                       x_payload_encoding: str = Header("identity"),
                       x_chunk_rows: int = Header(...),
                       x_chunk_checksum: str = Header(...)) -> Response:
    if seq < 0:
        raise storage.ValidationError("seq 必须 >= 0")
    meta = storage.dataset_meta(dataset_id)
    spec = meta["parts"].get(storage.check_part(part))
    if spec is None:
        raise storage.ValidationError(f"数据集 {dataset_id} 没有分区 {part}")
    if meta.get("status") != "UPLOADING":
        return JSONResponse(status_code=409, content={"detail": f"数据集状态为 {meta.get('status')}，不能继续上传"})
    declared = request.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > settings.max_chunk_bytes:
        return JSONResponse(status_code=413, content={"detail": "分片过大"})
    body = await request.body()
    if len(body) > settings.max_chunk_bytes:
        return JSONResponse(status_code=413, content={"detail": "分片过大"})
    actual = zlib.crc32(body) & 0xFFFFFFFF
    try:
        expected = int(x_chunk_checksum, 16)
    except ValueError:
        raise storage.ValidationError(f"X-Chunk-Checksum 非法: {x_chunk_checksum}") from None
    if actual != expected:
        return JSONResponse(status_code=422,
                            content={"detail": f"分片 CRC32 校验失败 expected={x_chunk_checksum} actual={actual:x}"})
    try:
        payload = await run_in_threadpool(_decode_chunk, body, x_payload_encoding)
    except (OSError, ValueError) as e:
        raise storage.ValidationError(f"分片解码失败: {e}") from e
    if payload.get("columns") != spec["columns"]:
        raise storage.ValidationError(f"分片列与声明不一致: {payload.get('columns')} != {spec['columns']}")
    if len(payload.get("rows", [])) != x_chunk_rows:
        raise storage.ValidationError(f"分片行数不一致: {len(payload.get('rows', []))} != {x_chunk_rows}")
    path = storage.chunk_path(dataset_id, part, seq)
    tmp = path.with_suffix(".tmp")
    tmp.write_bytes(body if x_payload_encoding == "gzip" else gzip.compress(body, compresslevel=1))
    tmp.replace(path)
    return Response(status_code=204)


def _complete(dataset_id: str, req: CompleteDatasetRequest) -> CompleteDatasetResponse:
    meta = storage.dataset_meta(dataset_id)
    if meta.get("status") == "COMPLETE":
        return CompleteDatasetResponse(dataset_id=dataset_id, rows=meta.get("rows", {}))
    missing: dict[str, list[int]] = {}
    for part, summary in req.parts.items():
        if part not in meta["parts"]:
            raise storage.ValidationError(f"未声明的分区: {part}")
        present = storage.list_chunks(dataset_id, part)
        lost = sorted(set(range(summary.chunks)) - present)
        if lost:
            missing[part] = lost
    if missing:
        log.warning("数据集 %s 缺少分片 %s", dataset_id, missing)
        return CompleteDatasetResponse(dataset_id=dataset_id, missing_chunks=missing)

    rows: dict[str, int] = {}
    for part, spec in meta["parts"].items():
        summary = req.parts.get(part)
        frames = []
        for seq in range(summary.chunks if summary else 0):
            payload = json.loads(gzip.decompress(storage.chunk_path(dataset_id, part, seq).read_bytes()))
            frames.append(pd.DataFrame(payload["rows"], columns=payload["columns"]))
        df = pd.concat(frames, ignore_index=True) if frames else pd.DataFrame(columns=spec["columns"])
        expected = summary.rows if summary else 0
        if len(df) != expected:
            raise storage.ValidationError(f"分区 {part} 行数不一致: 实际 {len(df)}，声明 {expected}")
        storage.save_part_frame(dataset_id, part, df)
        rows[part] = int(len(df))
    meta.update({"status": "COMPLETE", "rows": rows, "completed_at": time.time()})
    storage.save_dataset_meta(dataset_id, meta)
    log.info("数据集 %s 合并完成 rows=%s", dataset_id, rows)
    return CompleteDatasetResponse(dataset_id=dataset_id, rows=rows)


@app.post("/api/v1/datasets/{dataset_id}/complete", response_model=CompleteDatasetResponse)
async def complete_dataset(dataset_id: str, req: CompleteDatasetRequest) -> CompleteDatasetResponse:
    return await run_in_threadpool(_complete, dataset_id, req)


@app.delete("/api/v1/datasets/{dataset_id}", status_code=204)
def delete_dataset(dataset_id: str) -> Response:
    storage.delete_dataset(dataset_id)
    return Response(status_code=204)


# ------------------------------------------------------------------ async tasks

def _ref(state) -> EngineTaskRef:
    return EngineTaskRef(task_id=state.task_id, status=state.status)


@app.post("/api/v1/preprocess", response_model=EngineTaskRef)
def submit_preprocess(req: PreprocessRequest) -> EngineTaskRef:
    storage.dataset_meta(req.dataset_id)
    state = task_manager.submit(
        "PREPROCESS",
        lambda ctx: run_preprocess(ctx, req.dataset_id, req.task_type, req.targets, req.frequency, req.params),
        req.request_id, req.task_id)
    return _ref(state)


@app.post("/api/v1/train", response_model=EngineTaskRef)
def submit_train(req: TrainRequest) -> EngineTaskRef:
    storage.load_processed(req.processed_dataset_id)
    if req.algorithm not in algorithms.ALGORITHMS:
        raise storage.ValidationError(f"不支持的算法: {req.algorithm}，可选: {sorted(algorithms.ALGORITHMS)}")
    state = task_manager.submit(
        "TRAIN",
        lambda ctx: run_train(ctx, req.processed_dataset_id, req.task_type, req.targets, req.algorithm, req.params),
        req.request_id, req.task_id)
    return _ref(state)


@app.post("/api/v1/predict", response_model=EngineTaskRef)
def submit_predict(req: PredictRequest) -> EngineTaskRef:
    storage.model_meta(req.model_id)
    storage.dataset_meta(req.dataset_id)
    state = task_manager.submit(
        "PREDICT",
        lambda ctx: run_predict(ctx, req.model_id, req.dataset_id, req.forecast_start.isoformat(),
                                req.forecast_end.isoformat(), req.params),
        req.request_id, req.task_id)
    return _ref(state)


@app.get("/api/v1/tasks/{task_id}", response_model=EngineTaskStatus)
def get_task(task_id: str) -> EngineTaskStatus:
    state = task_manager.get(storage.check_id(task_id))
    return EngineTaskStatus(task_id=state.task_id, type=state.type, status=state.status, progress=state.progress,
                            message=state.message, result=state.result, error=state.error)


@app.post("/api/v1/tasks/{task_id}/cancel", response_model=EngineTaskRef)
def cancel_task(task_id: str) -> EngineTaskRef:
    return _ref(task_manager.cancel(storage.check_id(task_id)))


# ------------------------------------------------------------------ results / models

@app.get("/api/v1/predictions/{prediction_id}", response_model=PredictionPage)
def get_predictions(prediction_id: str, offset: int = Query(0, ge=0), limit: int = Query(5000, ge=1, le=50000)):
    df = storage.load_prediction(prediction_id)
    page = df.iloc[offset: offset + limit]
    rows = [PredictionRow(series_key=r.series_key, target=r.target, ts=pd.Timestamp(r.ts).to_pydatetime(),
                          value=None if pd.isna(r.value) else float(r.value),
                          lower=None if pd.isna(r.lower) else float(r.lower),
                          upper=None if pd.isna(r.upper) else float(r.upper))
            for r in page.itertuples(index=False)]
    return PredictionPage(prediction_id=prediction_id, total=int(len(df)), offset=offset, rows=rows)


@app.get("/api/v1/models/{model_id}")
def get_model(model_id: str) -> dict:
    return storage.model_meta(model_id)


@app.get("/api/v1/algorithms")
def list_algorithms() -> list[str]:
    return sorted(algorithms.ALGORITHMS)
