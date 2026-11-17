"""引擎接口测试（协议与存储部分）：分片校验、缺片检测、路径穿越防护、健康检查。"""
import gzip
import json
import zlib

import pytest
from fastapi.testclient import TestClient

from app.main import app



def chunk_body(columns, rows):
    body = gzip.compress(json.dumps({"columns": columns, "rows": rows}).encode())
    return body, format(zlib.crc32(body) & 0xFFFFFFFF, "x")


@pytest.fixture(scope="module")
def client():
    with TestClient(app) as c:
        yield c


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


def test_health(client):
    body = client.get("/health").json()
    assert body["status"] == "UP" and body["workers"] >= 1
