"""引擎配置（环境变量）。"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, default))
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    version: str = "1.0.0"
    # 数据目录：多实例部署时应挂载共享存储（NFS/云盘），使数据集、模型、任务状态可被任一实例访问
    data_dir: Path = field(default_factory=lambda: Path(os.getenv("ENGINE_DATA_DIR", "./engine_data")).resolve())
    # 异步任务工作线程数（训练为 CPU 密集，建议不超过 CPU 核数）
    workers: int = field(default_factory=lambda: _env_int("ENGINE_WORKERS", 4))
    # 单分片最大字节数（防止异常大包）
    max_chunk_bytes: int = field(default_factory=lambda: _env_int("ENGINE_MAX_CHUNK_BYTES", 64 * 1024 * 1024))
    # 临时数据（数据集、预测结果、任务状态）保留时长
    temp_ttl_hours: int = field(default_factory=lambda: _env_int("ENGINE_TEMP_TTL_HOURS", 48))
    # 模型保留天数，0 表示永久
    model_ttl_days: int = field(default_factory=lambda: _env_int("ENGINE_MODEL_TTL_DAYS", 0))
    # 访问令牌：设置后除 /health 外的请求须携带 X-Engine-Token（与 Java 侧 forecast.engine.token 一致）
    token: str = field(default_factory=lambda: os.getenv("ENGINE_TOKEN", ""))
    # 可选：注册到 Nacos（为空则不注册）
    nacos_addr: str = field(default_factory=lambda: os.getenv("NACOS_ADDR", ""))
    nacos_namespace: str = field(default_factory=lambda: os.getenv("NACOS_NAMESPACE", ""))
    service_name: str = field(default_factory=lambda: os.getenv("ENGINE_SERVICE_NAME", "forecast-python-engine"))
    advertise_ip: str = field(default_factory=lambda: os.getenv("ENGINE_ADVERTISE_IP", ""))
    port: int = field(default_factory=lambda: _env_int("ENGINE_PORT", 8000))


settings = Settings()
