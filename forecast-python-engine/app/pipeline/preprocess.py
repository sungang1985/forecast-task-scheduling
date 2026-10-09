"""数据预处理：清洗主数据（去重、规整时间网格、异常值处理、缺失值填补）并对齐外生变量。

预处理逻辑同时被预测阶段复用（对近期历史做相同处理），保证训练/预测特征一致。
按任务类型扩展：在 PREPROCESS_DEFAULTS 中为新任务类型配置默认参数即可。
"""
from __future__ import annotations

import logging
from typing import Any

import numpy as np
import pandas as pd

from .. import storage

log = logging.getLogger(__name__)

# 任务类型默认预处理参数（可被请求 params 覆盖）
PREPROCESS_DEFAULTS: dict[str, dict[str, Any]] = {
    # 负荷：不允许负值，IQR 异常值截断
    "LOAD": {"outlier_method": "iqr", "iqr_k": 3.0, "non_negative": True, "max_interp_points": 8},
    # 量价：价格可能为负、尖峰真实存在，采用分位数温和截断
    "PRICE": {"outlier_method": "quantile", "quantile_low": 0.002, "quantile_high": 0.998,
              "non_negative": False, "max_interp_points": 8},
}
DEFAULT_PARAMS: dict[str, Any] = {"outlier_method": "iqr", "iqr_k": 3.0, "non_negative": False, "max_interp_points": 8}


def resolve_params(task_type: str, params: dict[str, Any] | None) -> dict[str, Any]:
    merged = dict(PREPROCESS_DEFAULTS.get((task_type or "").upper(), DEFAULT_PARAMS))
    for key in ("outlier_method", "iqr_k", "quantile_low", "quantile_high", "non_negative", "max_interp_points"):
        if params and key in params:
            merged[key] = params[key]
    return merged


def points_per_day(freq: str) -> int:
    return int(pd.Timedelta("1D") / pd.Timedelta(freq))


def load_parts(dataset_id: str) -> tuple[dict[str, Any], dict[str, pd.DataFrame]]:
    meta = storage.dataset_meta(dataset_id)
    if meta.get("status") != "COMPLETE":
        raise storage.ValidationError(f"数据集 {dataset_id} 尚未完成上传")
    frames = {part: storage.load_part_frame(dataset_id, part) for part in meta["parts"]}
    return meta, frames


def _clip_outliers(series: pd.Series, cfg: dict[str, Any]) -> tuple[pd.Series, int]:
    valid = series.dropna()
    if len(valid) < 20:
        return series, 0
    method = cfg.get("outlier_method", "iqr")
    if method == "iqr":
        q1, q3 = valid.quantile(0.25), valid.quantile(0.75)
        k = float(cfg.get("iqr_k", 3.0))
        low, high = q1 - k * (q3 - q1), q3 + k * (q3 - q1)
    elif method == "quantile":
        low = valid.quantile(float(cfg.get("quantile_low", 0.005)))
        high = valid.quantile(float(cfg.get("quantile_high", 0.995)))
    else:
        return series, 0
    clipped = int(((series < low) | (series > high)).sum())
    return series.clip(lower=low, upper=high), clipped


def _fill_missing(series: pd.Series, ppd: int, max_interp: int) -> pd.Series:
    # 1) 短缺口：时间线性插值；2) 长缺口：取前一日/前一周同时段；3) 兜底：前后填充
    filled = series.interpolate(method="time", limit=max_interp, limit_area="inside")
    for shift in (ppd, 7 * ppd, -ppd):
        if not filled.isna().any():
            break
        filled = filled.fillna(filled.shift(shift))
    return filled.ffill().bfill()


def clean_primary(df: pd.DataFrame, targets: list[str], freq: str, cfg: dict[str, Any],
                  stats: dict[str, Any]) -> pd.DataFrame:
    missing = [t for t in targets if t not in df.columns]
    if missing:
        raise storage.ValidationError(f"主数据缺少目标字段: {missing}，现有字段: {list(df.columns)}")
    if df.empty:
        raise storage.ValidationError("主数据为空")
    df = df[["ts", "series_key", *targets]].copy()
    df["ts"] = pd.to_datetime(df["ts"])
    for t in targets:
        df[t] = pd.to_numeric(df[t], errors="coerce")
    before = len(df)
    df = df.drop_duplicates(subset=["series_key", "ts"], keep="last").sort_values(["series_key", "ts"])
    stats["duplicates_removed"] = stats.get("duplicates_removed", 0) + before - len(df)

    ppd = points_per_day(freq)
    frames = []
    for key, group in df.groupby("series_key", sort=True):
        g = group.set_index("ts")[targets]
        grid = pd.date_range(g.index.min().floor(freq), g.index.max(), freq=freq)
        g = g.reindex(grid)
        g.index.name = "ts"
        for t in targets:
            s = g[t]
            if cfg.get("non_negative"):
                negatives = int((s < 0).sum())
                stats["negatives_removed"] = stats.get("negatives_removed", 0) + negatives
                s = s.mask(s < 0)
            s, clipped = _clip_outliers(s, cfg)
            stats["outliers_clipped"] = stats.get("outliers_clipped", 0) + clipped
            missing_before = int(s.isna().sum())
            s = _fill_missing(s, ppd, int(cfg.get("max_interp_points", 8)))
            stats["missing_filled"] = stats.get("missing_filled", 0) + missing_before - int(s.isna().sum())
            g[t] = s
        g = g.reset_index()
        g.insert(1, "series_key", key)
        frames.append(g)
    return pd.concat(frames, ignore_index=True)


def extend_grid(frame: pd.DataFrame, targets: list[str], freq: str, end: pd.Timestamp) -> pd.DataFrame:
    """把每个序列的时间网格延长到 end（不含），未来时段目标值为 NaN（预测阶段使用）。"""
    frames = []
    for key, group in frame.groupby("series_key", sort=True):
        g = group.set_index("ts")
        grid = pd.date_range(g.index.min(), end - pd.Timedelta(freq), freq=freq)
        if len(grid) > len(g):
            g = g.reindex(grid)
            g.index.name = "ts"
            g["series_key"] = key
        frames.append(g.reset_index())
    return pd.concat(frames, ignore_index=True)


def align_exogenous(frame: pd.DataFrame, meta: dict[str, Any], parts: dict[str, pd.DataFrame], freq: str,
                    stats: dict[str, Any]) -> tuple[pd.DataFrame, list[str]]:
    """外生变量（不同频率、不同 key 体系）重采样到目标频率并与主数据对齐。"""
    exo_columns: list[str] = []
    for part, spec in meta["parts"].items():
        if spec.get("role") != "exogenous":
            continue
        fields = [c for c in spec["columns"] if c not in ("ts", "series_key")]
        prefixed = [f"{part}__{f}" for f in fields]
        exo_columns.extend(prefixed)
        df = parts.get(part)
        if df is None or df.empty:
            for c in prefixed:
                frame[c] = np.nan
            stats.setdefault("exogenous_empty", []).append(part)
            continue
        df = df.copy()
        df["ts"] = pd.to_datetime(df["ts"])
        for f in fields:
            df[f] = pd.to_numeric(df[f], errors="coerce")
        df = df.rename(columns=dict(zip(fields, prefixed)))

        def resample(g: pd.DataFrame) -> pd.DataFrame:
            r = g.set_index("ts")[prefixed].sort_index()
            r = r[~r.index.duplicated(keep="last")]
            return r.resample(freq).mean().interpolate(method="time", limit_area="inside")

        if spec.get("join_on_key", True):
            pieces = []
            for key, g in df.groupby("series_key"):
                r = resample(g).reset_index()
                r["series_key"] = key
                pieces.append(r)
            exo = pd.concat(pieces, ignore_index=True)
            frame = frame.merge(exo, on=["series_key", "ts"], how="left")
        else:
            exo = resample(df.groupby("ts", as_index=False)[prefixed].mean()).reset_index()
            frame = frame.merge(exo, on="ts", how="left")
        # 低频外生变量在边界处（如最后一个小时内的 15 分钟点）前向填充少量点
        frame[prefixed] = frame.groupby("series_key")[prefixed].ffill(limit=8)
    return frame, exo_columns


def build_frame(meta: dict[str, Any], parts: dict[str, pd.DataFrame], targets: list[str], freq: str,
                cfg: dict[str, Any], extend_to: pd.Timestamp | None = None) -> tuple[pd.DataFrame, list[str], dict]:
    stats: dict[str, Any] = {}
    primary = parts.get("primary")
    if primary is None:
        raise storage.ValidationError("数据集缺少 primary 分区")
    frame = clean_primary(primary, targets, freq, cfg, stats)
    if extend_to is not None:
        frame = extend_grid(frame, targets, freq, extend_to)
    frame, exo_columns = align_exogenous(frame, meta, parts, freq, stats)
    frame = frame.sort_values(["series_key", "ts"]).reset_index(drop=True)
    stats["series"] = int(frame["series_key"].nunique())
    stats["rows"] = int(len(frame))
    return frame, exo_columns, stats


def run_preprocess(ctx, dataset_id: str, task_type: str, targets: list[str], freq: str,
                   params: dict[str, Any]) -> dict[str, Any]:
    ctx.progress(5, "加载数据集")
    meta, parts = load_parts(dataset_id)
    ctx.check_cancelled()
    cfg = resolve_params(task_type, params)
    ctx.progress(20, "清洗与对齐")
    frame, exo_columns, stats = build_frame(meta, parts, targets, freq, cfg)
    ctx.check_cancelled()
    ctx.progress(90, "保存预处理结果")
    processed_id = storage.save_processed(frame, {
        "task_type": task_type, "targets": targets, "frequency": freq, "exogenous_columns": exo_columns,
        "preprocess": cfg, "source_dataset_id": dataset_id, "stats": stats,
    })
    log.info("预处理完成 processed=%s stats=%s", processed_id, stats)
    return {"processed_dataset_id": processed_id, "rows": stats["rows"], "series": stats["series"],
            "exogenous_columns": exo_columns, "stats": stats}
