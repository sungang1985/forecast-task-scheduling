"""算法注册表。新增算法：编写工厂函数并用 @register("名称") 注册，Java 侧通过 algorithm 参数选择。"""
from __future__ import annotations

from typing import Any, Callable

import numpy as np
from sklearn.ensemble import HistGradientBoostingRegressor, RandomForestRegressor
from sklearn.linear_model import Ridge
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

ALGORITHMS: dict[str, Callable[[dict[str, Any], list[str]], Any]] = {}


def register(name: str):
    def decorator(factory):
        ALGORITHMS[name] = factory
        return factory
    return decorator


def create(name: str, params: dict[str, Any], feature_names: list[str]):
    factory = ALGORITHMS.get(name)
    if factory is None:
        raise ValueError(f"不支持的算法: {name}，可选: {sorted(ALGORITHMS)}")
    return factory(params or {}, feature_names)


@register("gbrt")
def _gbrt(params: dict[str, Any], features: list[str]):
    return HistGradientBoostingRegressor(
        max_iter=int(params.get("max_iter", 300)),
        learning_rate=float(params.get("learning_rate", 0.06)),
        max_leaf_nodes=int(params.get("max_leaf_nodes", 31)),
        l2_regularization=float(params.get("l2_regularization", 0.1)),
        random_state=42,
    )


@register("rf")
def _rf(params: dict[str, Any], features: list[str]):
    return RandomForestRegressor(
        n_estimators=int(params.get("n_estimators", 120)),
        min_samples_leaf=int(params.get("min_samples_leaf", 2)),
        n_jobs=int(params.get("n_jobs", 1)),
        random_state=42,
    )


@register("linear")
def _linear(params: dict[str, Any], features: list[str]):
    return make_pipeline(StandardScaler(), Ridge(alpha=float(params.get("alpha", 1.0))))


class NaiveLagModel:
    """基准模型：直接使用最短滞后（如前一日同时段）作为预测值。"""

    def __init__(self, features: list[str]):
        lag_columns = [i for i, f in enumerate(features) if f.startswith("lag_")]
        self._index = lag_columns[0] if lag_columns else None
        self._mean = 0.0

    def fit(self, X, y):
        self._mean = float(np.nanmean(y)) if len(y) else 0.0
        return self

    def predict(self, X):
        X = np.asarray(X, dtype=float)
        if self._index is None:
            return np.full(len(X), self._mean)
        values = X[:, self._index]
        return np.where(np.isnan(values), self._mean, values)


@register("naive")
def _naive(params: dict[str, Any], features: list[str]):
    return NaiveLagModel(features)
