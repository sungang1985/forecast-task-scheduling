"""文件存储：数据集（分片 -> 合并）、预处理数据集、模型、预测结果、任务状态。

目录结构（ENGINE_DATA_DIR）：
    datasets/<dataset_id>/meta.json
    datasets/<dataset_id>/parts/<part>/chunk-000001.bin   上传中的分片
    datasets/<dataset_id>/<part>.pkl                     合并后的分区数据
    processed/<processed_id>.pkl + .json                 预处理结果
    models/<model_id>/model.joblib + meta.json           模型
    predictions/<prediction_id>.pkl                      预测结果
    tasks/<task_id>.json                                 异步任务状态
"""
from __future__ import annotations

import json
import os
import re
import shutil
import time
import uuid
from pathlib import Path
from typing import Any

import joblib
import pandas as pd

from .config import settings

_ID_PATTERN = re.compile(r"^[a-z]{2,4}_[0-9a-f]{32}$")
_PART_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


class NotFoundError(Exception):
    pass


class ValidationError(Exception):
    pass


def new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex}"


def check_id(value: str) -> str:
    """校验 ID，防止路径穿越。"""
    if not value or not _ID_PATTERN.match(value):
        raise ValidationError(f"非法 ID: {value}")
    return value


def check_part(value: str) -> str:
    if not value or not _PART_PATTERN.match(value):
        raise ValidationError(f"非法分区名: {value}")
    return value


def _root(*parts: str) -> Path:
    path = settings.data_dir.joinpath(*parts)
    path.mkdir(parents=True, exist_ok=True)
    return path


def write_json(path: Path, data: dict[str, Any]) -> None:
    tmp = path.with_suffix(path.suffix + f".{uuid.uuid4().hex[:6]}.tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, default=str), encoding="utf-8")
    os.replace(tmp, path)


def read_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise NotFoundError(str(path.name))
    return json.loads(path.read_text(encoding="utf-8"))


# ---------------- datasets ----------------

def dataset_dir(dataset_id: str) -> Path:
    return settings.data_dir / "datasets" / check_id(dataset_id)


def create_dataset(meta: dict[str, Any]) -> str:
    dataset_id = new_id("ds")
    directory = _root("datasets", dataset_id)
    meta = {**meta, "dataset_id": dataset_id, "status": "UPLOADING", "created_at": time.time()}
    write_json(directory / "meta.json", meta)
    return dataset_id


def dataset_meta(dataset_id: str) -> dict[str, Any]:
    directory = dataset_dir(dataset_id)
    if not directory.exists():
        raise NotFoundError(f"数据集不存在: {dataset_id}")
    return read_json(directory / "meta.json")


def save_dataset_meta(dataset_id: str, meta: dict[str, Any]) -> None:
    write_json(dataset_dir(dataset_id) / "meta.json", meta)


def chunk_path(dataset_id: str, part: str, seq: int) -> Path:
    directory = dataset_dir(dataset_id) / "parts" / check_part(part)
    directory.mkdir(parents=True, exist_ok=True)
    return directory / f"chunk-{seq:06d}.bin"


def list_chunks(dataset_id: str, part: str) -> set[int]:
    directory = dataset_dir(dataset_id) / "parts" / check_part(part)
    if not directory.exists():
        return set()
    return {int(p.stem.split("-")[1]) for p in directory.glob("chunk-*.bin")}


def save_part_frame(dataset_id: str, part: str, df: pd.DataFrame) -> None:
    df.to_pickle(dataset_dir(dataset_id) / f"{check_part(part)}.pkl")
    shutil.rmtree(dataset_dir(dataset_id) / "parts" / part, ignore_errors=True)


def load_part_frame(dataset_id: str, part: str) -> pd.DataFrame:
    path = dataset_dir(dataset_id) / f"{check_part(part)}.pkl"
    if not path.exists():
        raise NotFoundError(f"数据集 {dataset_id} 缺少分区 {part}")
    return pd.read_pickle(path)


def delete_dataset(dataset_id: str) -> bool:
    directory = dataset_dir(dataset_id)
    if directory.exists():
        shutil.rmtree(directory, ignore_errors=True)
        return True
    return False


# ---------------- processed datasets ----------------

def save_processed(df: pd.DataFrame, meta: dict[str, Any]) -> str:
    processed_id = new_id("pd")
    directory = _root("processed")
    df.to_pickle(directory / f"{processed_id}.pkl")
    write_json(directory / f"{processed_id}.json", {**meta, "processed_dataset_id": processed_id,
                                                    "created_at": time.time()})
    return processed_id


def load_processed(processed_id: str) -> tuple[pd.DataFrame, dict[str, Any]]:
    directory = settings.data_dir / "processed"
    path = directory / f"{check_id(processed_id)}.pkl"
    if not path.exists():
        raise NotFoundError(f"预处理数据集不存在: {processed_id}")
    return pd.read_pickle(path), read_json(directory / f"{processed_id}.json")


# ---------------- models ----------------

def save_model(bundle: dict[str, Any], meta: dict[str, Any]) -> str:
    model_id = new_id("md")
    directory = _root("models", model_id)
    joblib.dump(bundle, directory / "model.joblib", compress=3)
    write_json(directory / "meta.json", {**meta, "model_id": model_id, "created_at": time.time()})
    return model_id


def load_model(model_id: str) -> tuple[dict[str, Any], dict[str, Any]]:
    directory = settings.data_dir / "models" / check_id(model_id)
    if not (directory / "model.joblib").exists():
        raise NotFoundError(f"模型不存在: {model_id}")
    return joblib.load(directory / "model.joblib"), read_json(directory / "meta.json")


def model_meta(model_id: str) -> dict[str, Any]:
    return read_json(settings.data_dir / "models" / check_id(model_id) / "meta.json")


# ---------------- predictions ----------------

def save_prediction(df: pd.DataFrame) -> str:
    prediction_id = new_id("pr")
    df.to_pickle(_root("predictions") / f"{prediction_id}.pkl")
    return prediction_id


def load_prediction(prediction_id: str) -> pd.DataFrame:
    path = settings.data_dir / "predictions" / f"{check_id(prediction_id)}.pkl"
    if not path.exists():
        raise NotFoundError(f"预测结果不存在: {prediction_id}")
    return pd.read_pickle(path)


# ---------------- tasks ----------------

def task_path(task_id: str) -> Path:
    return _root("tasks") / f"{check_id(task_id)}.json"


# ---------------- housekeeping ----------------

def cleanup_expired() -> int:
    """清理过期的临时数据，返回删除的条目数。"""
    removed = 0
    now = time.time()
    temp_ttl = settings.temp_ttl_hours * 3600
    targets = [("datasets", temp_ttl), ("processed", temp_ttl), ("predictions", temp_ttl), ("tasks", temp_ttl)]
    if settings.model_ttl_days > 0:
        targets.append(("models", settings.model_ttl_days * 86400))
    for name, ttl in targets:
        directory = settings.data_dir / name
        if not directory.exists():
            continue
        for entry in directory.iterdir():
            try:
                if now - entry.stat().st_mtime > ttl:
                    if entry.is_dir():
                        shutil.rmtree(entry, ignore_errors=True)
                    else:
                        entry.unlink(missing_ok=True)
                    removed += 1
            except FileNotFoundError:
                continue
    return removed
