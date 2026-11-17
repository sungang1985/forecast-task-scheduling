"""可选：注册到 Nacos，使 Java 侧通过服务名 + LoadBalancer 发现引擎实例。

设置环境变量 NACOS_ADDR（如 nacos:8848）后启用。以持久化实例注册（由 Nacos 服务端做健康检查），
进程退出时注销。注册失败不影响引擎提供服务（Java 侧也可通过 base-url 直连）。
"""
from __future__ import annotations

import logging
import socket

import httpx

from .config import settings

log = logging.getLogger(__name__)


def _ip() -> str:
    if settings.advertise_ip:
        return settings.advertise_ip
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except OSError:
        return socket.gethostbyname(socket.gethostname())


def _params() -> dict[str, str]:
    params = {"serviceName": settings.service_name, "ip": _ip(), "port": str(settings.port),
              "ephemeral": "false", "metadata": '{"protocol":"http","version":"%s"}' % settings.version}
    if settings.nacos_namespace:
        params["namespaceId"] = settings.nacos_namespace
    return params


def register() -> None:
    if not settings.nacos_addr:
        return
    try:
        resp = httpx.post(f"http://{settings.nacos_addr}/nacos/v1/ns/instance", params=_params(), timeout=5)
        resp.raise_for_status()
        log.info("已注册到 Nacos %s: %s", settings.nacos_addr, _params())
    except Exception as e:  # noqa: BLE001
        log.warning("注册 Nacos 失败（可改用 base-url 直连）: %s", e)


def deregister() -> None:
    if not settings.nacos_addr:
        return
    try:
        httpx.delete(f"http://{settings.nacos_addr}/nacos/v1/ns/instance", params=_params(), timeout=5)
    except Exception as e:  # noqa: BLE001
        log.warning("Nacos 注销失败: %s", e)
