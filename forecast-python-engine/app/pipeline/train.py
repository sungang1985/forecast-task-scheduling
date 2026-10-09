"""模型训练：按 (预测对象, 目标) 分别训练，时间顺序切分验证集评估，再全量重训。"""
from __future__ import annotations

import logging
from typing import Any

import numpy as np
import pandas as pd

from .. import storage
from . import algorithms
from .features import build_features, feature_names
from .preprocess import points_per_day

log = logging.getLogger(__name__)


def resolve_lags(params: dict[str, Any], ppd: int) -> list[int]:
    """滞后点数（默认 1 天、7 天）。只接受正整数列表，非法配置直接报错而不是静默降级。"""
    lags = params.get("lags")
    if lags is None:
        return [ppd, 7 * ppd]
    if isinstance(lags, dict):  # 兼容以 "0","1"... 为键的列表序列化形式
        lags = [lags[k] for k in sorted(lags, key=lambda k: int(k))]
    if not isinstance(lags, (list, tuple)) or not lags:
        raise storage.ValidationError(f"lags 必须是非空整数列表: {lags!r}")
    try:
        values = sorted({int(lag) for lag in lags})
    except (TypeError, ValueError):
        raise storage.ValidationError(f"lags 必须是整数列表: {lags!r}") from None
    if values[0] < 1:
        raise storage.ValidationError(f"lags 必须为正整数: {lags!r}")
    return values


def _metrics(y_true: np.ndarray, y_pred: np.ndarray) -> dict[str, float]:
    err = y_pred - y_true
    mae = float(np.mean(np.abs(err)))
    rmse = float(np.sqrt(np.mean(err ** 2)))
    nonzero = np.abs(y_true) > 1e-6
    mape = float(np.mean(np.abs(err[nonzero] / y_true[nonzero])) * 100) if nonzero.any() else float("nan")
    return {"mae": round(mae, 4), "rmse": round(rmse, 4), "mape": round(mape, 4)}


def run_train(ctx, processed_id: str, task_type: str, targets: list[str], algorithm: str,
              params: dict[str, Any]) -> dict[str, Any]:
    ctx.progress(2, "加载预处理数据集")
    frame, meta = storage.load_processed(processed_id)
    freq = meta["frequency"]
    ppd = points_per_day(freq)
    lags = resolve_lags(params, ppd)
    exo_columns: list[str] = meta.get("exogenous_columns", [])
    features = feature_names(lags, exo_columns)
    missing = [t for t in targets if t not in frame.columns]
    if missing:
        raise storage.ValidationError(f"预处理数据缺少目标: {missing}")

    validation_ratio = float(params.get("validation_ratio", 0.15))
    min_rows = int(params.get("min_train_rows", 2 * ppd))
    series_keys = sorted(frame["series_key"].unique())
    total_jobs = max(1, len(series_keys) * len(targets))
    models: dict[str, Any] = {}
    medians: dict[str, dict[str, float]] = {}
    residual_std: dict[str, float] = {}
    series_metrics: dict[str, dict[str, Any]] = {}
    train_rows = 0
    done = 0

    for key in series_keys:
        g = frame[frame["series_key"] == key].sort_values("ts").reset_index(drop=True)
        exo = g[exo_columns].to_numpy(dtype=float) if exo_columns else None
        for target in targets:
            ctx.check_cancelled()
            y = g[target].to_numpy(dtype=float)
            X = build_features(g["ts"], y, exo, np.arange(len(g)), lags, ppd, exo_columns)
            mask = ~np.isnan(y) & ~np.isnan(X[f"lag_{lags[0]}"].to_numpy())
            if mask.sum() < min_rows:
                raise storage.ValidationError(
                    f"{key}/{target} 有效训练样本不足（{int(mask.sum())} < {min_rows}），请扩大历史窗口")
            Xv, yv = X[mask].reset_index(drop=True), y[mask]
            col_medians = Xv.median(numeric_only=True).fillna(0.0)
            Xv = Xv.fillna(col_medians)

            split = int(len(Xv) * (1 - validation_ratio))
            model = algorithms.create(algorithm, params, features)
            model.fit(Xv.iloc[:split].to_numpy(), yv[:split])
            pred = model.predict(Xv.iloc[split:].to_numpy())
            m = _metrics(yv[split:], pred)
            residual = float(np.std(yv[split:] - pred)) if len(pred) > 1 else 0.0
            if params.get("refit", True):
                model = algorithms.create(algorithm, params, features)
                model.fit(Xv.to_numpy(), yv)

            model_key = f"{key}|{target}"
            models[model_key] = model
            medians[model_key] = {k: float(v) for k, v in col_medians.items()}
            residual_std[model_key] = residual
            series_metrics.setdefault(key, {})[target] = {**m, "samples": int(len(yv))}
            train_rows += int(len(yv))
            done += 1
            ctx.progress(5 + 90 * done / total_jobs, f"已训练 {done}/{total_jobs}（{key}/{target} MAPE={m['mape']}）")
            log.info("训练完成 series=%s target=%s samples=%d metrics=%s", key, target, len(yv), m)

    overall: dict[str, Any] = {}
    for target in targets:
        values = [series_metrics[k][target] for k in series_keys]
        overall[target] = {name: round(float(np.nanmean([v[name] for v in values])), 4)
                           for name in ("mae", "rmse", "mape")}

    bundle = {
        "task_type": task_type, "algorithm": algorithm, "targets": targets, "frequency": freq, "ppd": ppd,
        "lags": lags, "exogenous_columns": exo_columns, "features": features, "models": models,
        "medians": medians, "residual_std": residual_std, "series": series_keys,
        "preprocess": meta.get("preprocess", {}),
        "train_end": str(pd.Timestamp(frame["ts"].max())),
    }
    ctx.progress(97, "保存模型")
    model_id = storage.save_model(bundle, {
        "task_type": task_type, "algorithm": algorithm, "targets": targets, "series": series_keys,
        "metrics": {"overall": overall, "series": series_metrics}, "processed_dataset_id": processed_id,
        "features": features,
    })
    return {"model_id": model_id, "algorithm": algorithm, "train_rows": train_rows, "features": features,
            "metrics": {"overall": overall, "series": series_metrics}}
