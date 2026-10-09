"""接口协议模型（字段使用 snake_case，与 Java 侧 EngineDtos 对应）。"""
from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field


class PartSpec(BaseModel):
    source: str | None = None
    role: str = "target"
    columns: list[str]
    join_on_key: bool = True


class CreateDatasetRequest(BaseModel):
    task_id: str | None = None
    task_type: str
    frequency: str = "15min"
    parts: dict[str, PartSpec]


class CreateDatasetResponse(BaseModel):
    dataset_id: str


class PartSummary(BaseModel):
    chunks: int = Field(ge=0)
    rows: int = Field(ge=0)


class CompleteDatasetRequest(BaseModel):
    parts: dict[str, PartSummary]


class CompleteDatasetResponse(BaseModel):
    dataset_id: str
    rows: dict[str, int] = {}
    missing_chunks: dict[str, list[int]] = {}


class PreprocessRequest(BaseModel):
    request_id: str | None = None
    task_id: str | None = None
    task_type: str
    dataset_id: str
    targets: list[str]
    frequency: str = "15min"
    params: dict[str, Any] = {}


class TrainRequest(BaseModel):
    request_id: str | None = None
    task_id: str | None = None
    task_type: str
    processed_dataset_id: str
    targets: list[str]
    algorithm: str = "gbrt"
    params: dict[str, Any] = {}


class PredictRequest(BaseModel):
    request_id: str | None = None
    task_id: str | None = None
    task_type: str
    model_id: str
    dataset_id: str
    forecast_start: datetime
    forecast_end: datetime
    params: dict[str, Any] = {}


class EngineTaskRef(BaseModel):
    task_id: str
    status: str


class EngineTaskStatus(BaseModel):
    task_id: str
    type: str
    status: str
    progress: int = 0
    message: str | None = None
    result: dict[str, Any] | None = None
    error: str | None = None


class PredictionRow(BaseModel):
    series_key: str
    target: str
    ts: datetime
    value: float | None
    lower: float | None
    upper: float | None


class PredictionPage(BaseModel):
    prediction_id: str
    total: int
    offset: int
    rows: list[PredictionRow]


class EngineHealth(BaseModel):
    status: str
    version: str
    workers: int
    running_tasks: int
    queued_tasks: int
