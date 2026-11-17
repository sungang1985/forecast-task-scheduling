"""结果预测：对近期历史做与训练一致的预处理，按最短滞后步长分块递推预测（未来时段的滞后特征使用已预测值）。"""
from __future__ import annotations

import logging
from typing import Any

import numpy as np
import pandas as pd

from .. import storage
from .features import build_features
from .preprocess import build_frame, load_parts

log = logging.getLogger(__name__)

Z_95 = 1.96


def run_predict(ctx, model_id: str, dataset_id: str, forecast_start: str, forecast_end: str,
                params: dict[str, Any]) -> dict[str, Any]:
    ctx.progress(2, "加载模型与数据")
    bundle, _ = storage.load_model(model_id)
    meta, parts = load_parts(dataset_id)
    start, end = pd.Timestamp(forecast_start), pd.Timestamp(forecast_end)
    if start >= end:
        raise storage.ValidationError("forecast_start 必须早于 forecast_end")

    targets: list[str] = bundle["targets"]
    freq, ppd, lags = bundle["frequency"], bundle["ppd"], bundle["lags"]
    exo_columns: list[str] = bundle["exogenous_columns"]
    frame, data_exo, _ = build_frame(meta, parts, targets, freq, bundle.get("preprocess", {}), extend_to=end)
    for col in exo_columns:
        if col not in frame.columns:
            frame[col] = np.nan
    non_negative = bool(bundle.get("preprocess", {}).get("non_negative"))
    block = max(1, min(lags))

    outputs = []
    series_keys = [k for k in sorted(frame["series_key"].unique()) if k in set(bundle["series"])]
    if not series_keys:
        raise storage.ValidationError(f"数据中的预测对象与模型不匹配，模型覆盖: {bundle['series']}")
    total_jobs = len(series_keys) * len(targets)
    done = 0
    for key in series_keys:
        g = frame[frame["series_key"] == key].sort_values("ts").reset_index(drop=True)
        exo = g[exo_columns].to_numpy(dtype=float) if exo_columns else None
        ts = g["ts"]
        for target in targets:
            ctx.check_cancelled()
            model_key = f"{key}|{target}"
            model = bundle["models"].get(model_key)
            if model is None:
                log.warning("模型中没有 %s，跳过", model_key)
                continue
            y = g[target].to_numpy(dtype=float).copy()
            known = np.where(~np.isnan(y))[0]
            if len(known) == 0:
                raise storage.ValidationError(f"{key}/{target} 没有历史数据，无法预测")
            history_end = int(known[-1]) + 1
            medians = pd.Series(bundle["medians"][model_key])
            i = history_end
            while i < len(g):
                j = min(i + block, len(g))
                X = build_features(ts, y, exo, np.arange(i, j), lags, ppd, exo_columns).fillna(medians)
                pred = model.predict(X.to_numpy())
                if non_negative:
                    pred = np.maximum(pred, 0)
                y[i:j] = pred
                i = j
            window = (ts >= start) & (ts < end) & (np.arange(len(g)) >= history_end)
            std = bundle["residual_std"].get(model_key, 0.0)
            values = y[window.to_numpy()]
            lower = values - Z_95 * std
            if non_negative:
                lower = np.maximum(lower, 0)
            outputs.append(pd.DataFrame({
                "series_key": key, "target": target, "ts": ts[window].to_numpy(),
                "value": np.round(values, 4), "lower": np.round(lower, 4),
                "upper": np.round(values + Z_95 * std, 4),
            }))
            done += 1
            ctx.progress(5 + 90 * done / total_jobs, f"已预测 {done}/{total_jobs}")

    result = pd.concat(outputs, ignore_index=True) if outputs else pd.DataFrame(
        columns=["series_key", "target", "ts", "value", "lower", "upper"])
    if result.empty:
        raise storage.ValidationError("预测结果为空，请检查预测时间窗口与历史数据")
    prediction_id = storage.save_prediction(result)
    log.info("预测完成 prediction=%s rows=%d model=%s", prediction_id, len(result), model_id)
    return {"prediction_id": prediction_id, "rows": int(len(result)), "series": len(series_keys),
            "targets": targets, "model_id": model_id,
            "missing_exogenous": sorted(set(exo_columns) - set(data_exo))}
