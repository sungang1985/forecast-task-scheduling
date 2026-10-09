"""特征工程：日历特征 + 目标滞后特征 + 外生变量。训练与预测共用，保证特征一致。"""
from __future__ import annotations

import numpy as np
import pandas as pd

CALENDAR_FEATURES = ["hour", "slot", "dow", "month", "is_weekend", "doy_sin", "doy_cos", "slot_sin", "slot_cos"]


def feature_names(lags: list[int], exo_columns: list[str]) -> list[str]:
    return CALENDAR_FEATURES + [f"lag_{lag}" for lag in lags] + list(exo_columns)


def build_features(ts: pd.Series, y: np.ndarray, exo: np.ndarray | None, idx: np.ndarray, lags: list[int],
                   ppd: int, exo_columns: list[str]) -> pd.DataFrame:
    """为 idx 指定的行构造特征。

    :param ts: 序列时间（按时间升序、等间隔）
    :param y: 目标值数组（预测阶段包含已预测的值，用于递推滞后特征）
    :param exo: 外生变量矩阵（行与 ts 对齐）
    """
    t = pd.DatetimeIndex(ts.to_numpy()[idx])
    minutes_per_point = 1440 // ppd
    slot = (t.hour * 60 + t.minute) // minutes_per_point
    dow = t.dayofweek
    doy = t.dayofyear
    data: dict[str, np.ndarray] = {
        "hour": t.hour.to_numpy(),
        "slot": np.asarray(slot),
        "dow": dow.to_numpy(),
        "month": t.month.to_numpy(),
        "is_weekend": (dow >= 5).astype(int),
        "doy_sin": np.sin(2 * np.pi * doy / 365.25),
        "doy_cos": np.cos(2 * np.pi * doy / 365.25),
        "slot_sin": np.sin(2 * np.pi * np.asarray(slot) / ppd),
        "slot_cos": np.cos(2 * np.pi * np.asarray(slot) / ppd),
    }
    for lag in lags:
        src = idx - lag
        values = np.full(len(idx), np.nan)
        valid = src >= 0
        values[valid] = y[src[valid]]
        data[f"lag_{lag}"] = values
    for i, col in enumerate(exo_columns):
        data[col] = exo[idx, i] if exo is not None else np.full(len(idx), np.nan)
    return pd.DataFrame(data, columns=feature_names(lags, exo_columns))
