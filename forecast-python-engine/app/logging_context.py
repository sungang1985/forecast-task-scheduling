"""日志上下文：在日志中输出 Java 侧透传的 traceId / taskId，实现跨语言链路关联。"""
from __future__ import annotations

import contextvars
import logging

trace_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("trace_id", default="-")
task_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("task_id", default="-")


class ContextFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.trace_id = trace_id_var.get()
        record.task_id = task_id_var.get()
        return True


def setup_logging(level: int = logging.INFO) -> None:
    handler = logging.StreamHandler()
    handler.addFilter(ContextFilter())
    handler.setFormatter(logging.Formatter(
        "%(asctime)s %(levelname)5s [forecast-python-engine,%(trace_id)s,%(task_id)s] %(threadName)s "
        "%(name)s : %(message)s"))
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(level)
