"""异步任务管理：预处理/训练/预测在线程池中执行，Java 侧轮询状态。

- request_id 去重：同一提交请求重试时返回同一任务，保证调用方重试安全；
- 状态落盘：任务状态写入 tasks/<id>.json，进程重启或多实例共享存储时仍可查询；
- 协作式取消：任务函数在关键位置调用 ctx.check_cancelled()。
"""
from __future__ import annotations

import logging
import threading
import time
import traceback
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import asdict, dataclass, field
from typing import Any, Callable

from . import storage
from .config import settings
from .logging_context import task_id_var, trace_id_var

log = logging.getLogger(__name__)

PENDING, RUNNING, SUCCESS, FAILED, CANCELLED = "PENDING", "RUNNING", "SUCCESS", "FAILED", "CANCELLED"
TERMINAL = {SUCCESS, FAILED, CANCELLED}


class TaskCancelled(Exception):
    pass


@dataclass
class TaskState:
    task_id: str
    type: str
    status: str = PENDING
    progress: int = 0
    message: str | None = None
    result: dict[str, Any] | None = None
    error: str | None = None
    request_id: str | None = None
    caller_task_id: str | None = None
    trace_id: str | None = None
    created_at: float = field(default_factory=time.time)
    started_at: float | None = None
    finished_at: float | None = None


class TaskContext:
    """传给任务函数的上下文：汇报进度、检查取消。"""

    def __init__(self, manager: "TaskManager", state: TaskState):
        self._manager = manager
        self._state = state
        self._last_persist = 0.0

    def progress(self, percent: float, message: str | None = None) -> None:
        self._state.progress = max(0, min(99, int(percent)))
        if message:
            self._state.message = message
        now = time.time()
        if now - self._last_persist > 1:
            self._last_persist = now
            self._manager.persist(self._state)

    def check_cancelled(self) -> None:
        if self._manager.is_cancel_requested(self._state.task_id):
            raise TaskCancelled("任务已被取消")


class TaskManager:
    def __init__(self, workers: int):
        self._executor = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="engine-worker")
        self._lock = threading.Lock()
        self._tasks: dict[str, TaskState] = {}
        self._futures: dict[str, Future] = {}
        self._by_request: dict[str, str] = {}
        self._cancel_requested: set[str] = set()
        self.workers = workers

    def submit(self, task_type: str, fn: Callable[[TaskContext], dict[str, Any]], request_id: str | None,
               caller_task_id: str | None = None) -> TaskState:
        with self._lock:
            if request_id and request_id in self._by_request:
                return self._tasks[self._by_request[request_id]]
            state = TaskState(task_id=storage.new_id("tk"), type=task_type, request_id=request_id,
                              caller_task_id=caller_task_id, trace_id=trace_id_var.get())
            self._tasks[state.task_id] = state
            if request_id:
                self._by_request[request_id] = state.task_id
        self.persist(state)
        self._futures[state.task_id] = self._executor.submit(self._run, state, fn)
        log.info("任务已提交 type=%s engineTaskId=%s requestId=%s", task_type, state.task_id, request_id)
        return state

    def _run(self, state: TaskState, fn: Callable[[TaskContext], dict[str, Any]]) -> None:
        trace_token = trace_id_var.set(state.trace_id or "-")
        task_token = task_id_var.set(state.caller_task_id or "-")
        try:
            if state.task_id in self._cancel_requested:
                state.status = CANCELLED
                return
            state.status = RUNNING
            state.started_at = time.time()
            self.persist(state)
            begin = time.time()
            result = fn(TaskContext(self, state))
            state.result = result
            state.progress = 100
            state.status = SUCCESS
            log.info("任务完成 type=%s engineTaskId=%s cost=%.2fs", state.type, state.task_id, time.time() - begin)
        except TaskCancelled as e:
            state.status = CANCELLED
            state.message = str(e)
            log.info("任务已取消 engineTaskId=%s", state.task_id)
        except Exception as e:  # noqa: BLE001 - 任务失败需记录任意异常
            state.status = FAILED
            state.error = f"{type(e).__name__}: {e}"
            log.error("任务失败 engineTaskId=%s: %s\n%s", state.task_id, state.error, traceback.format_exc())
        finally:
            state.finished_at = time.time()
            self.persist(state)
            self._futures.pop(state.task_id, None)
            trace_id_var.reset(trace_token)
            task_id_var.reset(task_token)

    def get(self, task_id: str) -> TaskState:
        state = self._tasks.get(task_id)
        if state is not None:
            return state
        # 其他实例提交的任务（共享存储）或进程重启前的任务
        data = storage.read_json(storage.task_path(task_id))
        return TaskState(**{k: v for k, v in data.items() if k in TaskState.__dataclass_fields__})

    def cancel(self, task_id: str) -> TaskState:
        state = self.get(task_id)
        if state.status in TERMINAL:
            return state
        self._cancel_requested.add(task_id)
        future = self._futures.get(task_id)
        if future is not None and future.cancel():
            state.status = CANCELLED
            state.finished_at = time.time()
            self.persist(state)
        return state

    def is_cancel_requested(self, task_id: str) -> bool:
        return task_id in self._cancel_requested

    def persist(self, state: TaskState) -> None:
        try:
            storage.write_json(storage.task_path(state.task_id), asdict(state))
        except OSError as e:
            log.warning("任务状态落盘失败 %s: %s", state.task_id, e)

    def stats(self) -> dict[str, int]:
        running = sum(1 for s in self._tasks.values() if s.status == RUNNING)
        queued = sum(1 for s in self._tasks.values() if s.status == PENDING)
        return {"running": running, "queued": queued}

    def evict_finished(self, older_than_seconds: int = 3600) -> None:
        """清理内存中已结束的任务（状态文件仍保留至 TTL）。"""
        now = time.time()
        with self._lock:
            for task_id, state in list(self._tasks.items()):
                if state.status in TERMINAL and state.finished_at and now - state.finished_at > older_than_seconds:
                    self._tasks.pop(task_id, None)
                    self._cancel_requested.discard(task_id)
                    if state.request_id:
                        self._by_request.pop(state.request_id, None)

    def shutdown(self) -> None:
        self._executor.shutdown(wait=False, cancel_futures=True)


task_manager = TaskManager(settings.workers)
