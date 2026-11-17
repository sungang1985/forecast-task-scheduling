"""引擎接口测试：分批上传 -> 预处理 -> 训练 -> 预测 -> 分页拉取，以及校验/去重/缺片等异常路径。"""
import gzip
import json
import math
import time
import zlib
from datetime import datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from app.main import app

FREQ_MIN = 15
DAYS = 30
START = datetime(2026, 1, 1)


def load_value(area: int, t: datetime) -> float:
    hour = t.hour + t.minute / 60
    shape = 0.6 + 0.3 * math.exp(-((hour - 10.5) ** 2) / 6) + 0.35 * math.exp(-((hour - 19.5) ** 2) / 5)
    week = 0.85 if t.weekday() >= 5 else 1.0
    return round(1000 * (1 + area * 0.3) * shape * week, 3)


def primary_rows(days=DAYS):
    rows = []
    for area in range(2):
        for i in range(days * 96):
            t = START + timedelta(minutes=FREQ_MIN * i)
            value = None if i % 500 == 7 else load_value(area, t)  # 少量缺失
            rows.append([t.isoformat(), f"AREA_0{area + 1}", value])
    return rows


def weather_rows(days=DAYS + 2):
    rows = []
    for area in range(2):
        for h in range(days * 24):
            t = START + timedelta(hours=h)
            rows.append([t.isoformat(), f"AREA_0{area + 1}", round(15 + 8 * math.sin(h / 24 * 2 * math.pi), 2)])
    return rows


def chunk_body(columns, rows):
    body = gzip.compress(json.dumps({"columns": columns, "rows": rows}).encode())
    return body, format(zlib.crc32(body) & 0xFFFFFFFF, "x")


@pytest.fixture(scope="module")
def client():
    with TestClient(app) as c:
        yield c


def wait_task(client, task_id, timeout=120):
    deadline = time.time() + timeout
    while time.time() < deadline:
        status = client.get(f"/api/v1/tasks/{task_id}").json()
        if status["status"] in ("SUCCESS", "FAILED", "CANCELLED"):
            return status
        time.sleep(0.2)
    raise AssertionError(f"task {task_id} timeout")


def upload(client, dataset_id, part, columns, rows, chunk_rows):
    chunks = 0
    for seq, offset in enumerate(range(0, len(rows), chunk_rows)):
        part_rows = rows[offset: offset + chunk_rows]
        body, crc = chunk_body(columns, part_rows)
        resp = client.put(f"/api/v1/datasets/{dataset_id}/parts/{part}/chunks/{seq}", content=body, headers={
            "X-Payload-Encoding": "gzip", "X-Chunk-Rows": str(len(part_rows)), "X-Chunk-Checksum": crc,
            "Content-Type": "application/octet-stream"})
        assert resp.status_code == 204, resp.text
        chunks += 1
    return chunks


def create_dataset(client, primary, weather):
    resp = client.post("/api/v1/datasets", json={
        "task_id": "FT-test", "task_type": "LOAD", "frequency": "15min",
        "parts": {
            "primary": {"source": "load", "role": "target", "columns": ["ts", "series_key", "load"]},
            "weather": {"source": "weather", "role": "exogenous", "columns": ["ts", "series_key", "temperature"],
                        "join_on_key": True},
        }})
    assert resp.status_code == 200, resp.text
    dataset_id = resp.json()["dataset_id"]
    c1 = upload(client, dataset_id, "primary", ["ts", "series_key", "load"], primary, 1500)
    c2 = upload(client, dataset_id, "weather", ["ts", "series_key", "temperature"], weather, 700)
    resp = client.post(f"/api/v1/datasets/{dataset_id}/complete", json={"parts": {
        "primary": {"chunks": c1, "rows": len(primary)}, "weather": {"chunks": c2, "rows": len(weather)}}})
    assert resp.status_code == 200, resp.text
    assert not resp.json()["missing_chunks"]
    return dataset_id


def test_full_flow(client):
    dataset_id = create_dataset(client, primary_rows(), weather_rows())

    ref = client.post("/api/v1/preprocess", json={"request_id": "r-pre", "task_type": "LOAD", "dataset_id": dataset_id,
                                                   "targets": ["load"], "frequency": "15min", "params": {}}).json()
    status = wait_task(client, ref["task_id"])
    assert status["status"] == "SUCCESS", status
    processed_id = status["result"]["processed_dataset_id"]
    assert status["result"]["stats"]["missing_filled"] > 0
    assert "weather__temperature" in status["result"]["exogenous_columns"]

    ref = client.post("/api/v1/train", json={"request_id": "r-train", "task_type": "LOAD",
                                              "processed_dataset_id": processed_id, "targets": ["load"],
                                              "algorithm": "gbrt", "params": {"max_iter": 60}}).json()
    status = wait_task(client, ref["task_id"])
    assert status["status"] == "SUCCESS", status
    model_id = status["result"]["model_id"]
    assert status["result"]["metrics"]["overall"]["load"]["mape"] < 10

    start = START + timedelta(days=DAYS + 1)  # 与历史之间留一天空档，验证递推预测
    ref = client.post("/api/v1/predict", json={"request_id": "r-pred", "task_type": "LOAD", "model_id": model_id,
                                                "dataset_id": dataset_id, "forecast_start": start.isoformat(),
                                                "forecast_end": (start + timedelta(days=1)).isoformat()}).json()
    status = wait_task(client, ref["task_id"])
    assert status["status"] == "SUCCESS", status
    prediction_id = status["result"]["prediction_id"]
    assert status["result"]["rows"] == 2 * 96

    page1 = client.get(f"/api/v1/predictions/{prediction_id}?offset=0&limit=100").json()
    page2 = client.get(f"/api/v1/predictions/{prediction_id}?offset=100&limit=100").json()
    assert page1["total"] == 192 and len(page1["rows"]) == 100 and len(page2["rows"]) == 92
    first = page1["rows"][0]
    assert first["ts"].startswith(start.date().isoformat())
    assert first["lower"] <= first["value"] <= first["upper"]
    # 预测值量级合理（与真实曲线误差不大）
    actual = load_value(0, datetime.fromisoformat(first["ts"]))
    assert abs(first["value"] - actual) / actual < 0.2

    assert client.delete(f"/api/v1/datasets/{dataset_id}").status_code == 204


def test_request_id_dedupe(client):
    dataset_id = create_dataset(client, primary_rows(10), weather_rows(10))
    body = {"request_id": "dup-1", "task_type": "LOAD", "dataset_id": dataset_id, "targets": ["load"]}
    a = client.post("/api/v1/preprocess", json=body).json()
    b = client.post("/api/v1/preprocess", json=body).json()
    assert a["task_id"] == b["task_id"]
    wait_task(client, a["task_id"])


def test_checksum_and_missing_chunk(client):
    resp = client.post("/api/v1/datasets", json={"task_type": "LOAD", "parts": {
        "primary": {"columns": ["ts", "series_key", "load"]}}})
    dataset_id = resp.json()["dataset_id"]
    body, crc = chunk_body(["ts", "series_key", "load"], [["2026-01-01T00:00:00", "A", 1.0]])
    bad = client.put(f"/api/v1/datasets/{dataset_id}/parts/primary/chunks/0", content=body, headers={
        "X-Payload-Encoding": "gzip", "X-Chunk-Rows": "1", "X-Chunk-Checksum": "deadbeef"})
    assert bad.status_code == 422
    wrong_rows = client.put(f"/api/v1/datasets/{dataset_id}/parts/primary/chunks/0", content=body, headers={
        "X-Payload-Encoding": "gzip", "X-Chunk-Rows": "2", "X-Chunk-Checksum": crc})
    assert wrong_rows.status_code == 400
    ok = client.put(f"/api/v1/datasets/{dataset_id}/parts/primary/chunks/0", content=body, headers={
        "X-Payload-Encoding": "gzip", "X-Chunk-Rows": "1", "X-Chunk-Checksum": crc})
    assert ok.status_code == 204
    resp = client.post(f"/api/v1/datasets/{dataset_id}/complete",
                       json={"parts": {"primary": {"chunks": 3, "rows": 3}}}).json()
    assert resp["missing_chunks"] == {"primary": [1, 2]}


def test_path_traversal_rejected(client):
    assert client.get("/api/v1/tasks/..%2F..%2Fetc").status_code in (400, 404)
    assert client.delete("/api/v1/datasets/ds_notavalidid").status_code == 400


def test_unknown_algorithm(client):
    dataset_id = create_dataset(client, primary_rows(10), weather_rows(10))
    ref = client.post("/api/v1/preprocess", json={"task_type": "LOAD", "dataset_id": dataset_id,
                                                   "targets": ["load"]}).json()
    processed = wait_task(client, ref["task_id"])["result"]["processed_dataset_id"]
    resp = client.post("/api/v1/train", json={"task_type": "LOAD", "processed_dataset_id": processed,
                                               "targets": ["load"], "algorithm": "nope"})
    assert resp.status_code == 400


def test_health(client):
    body = client.get("/health").json()
    assert body["status"] == "UP" and body["workers"] >= 1


def test_engine_token(client, monkeypatch):
    import dataclasses
    import app.main as main_module
    monkeypatch.setattr(main_module, "settings", dataclasses.replace(main_module.settings, token="s3cret"))
    assert client.get("/health").status_code == 200
    assert client.get("/api/v1/algorithms").status_code == 401
    assert client.get("/api/v1/algorithms", headers={"X-Engine-Token": "wrong"}).status_code == 401
    assert client.get("/api/v1/algorithms", headers={"X-Engine-Token": "s3cret"}).status_code == 200


def test_resolve_lags():
    from app import storage
    from app.pipeline.train import resolve_lags
    assert resolve_lags({}, 96) == [96, 672]
    assert resolve_lags({"lags": [672, 96]}, 96) == [96, 672]
    assert resolve_lags({"lags": {"0": 96, "1": 672}}, 96) == [96, 672]
    for bad in ([0], "96", [], ["x"]):
        with pytest.raises(storage.ValidationError):
            resolve_lags({"lags": bad}, 96)
