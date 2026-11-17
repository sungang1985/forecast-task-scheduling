import os
import shutil
import tempfile

# 必须在导入 app 之前设置数据目录
_DATA_DIR = tempfile.mkdtemp(prefix="engine-test-")
os.environ["ENGINE_DATA_DIR"] = _DATA_DIR
os.environ.setdefault("ENGINE_WORKERS", "2")


def pytest_sessionfinish(session, exitstatus):
    shutil.rmtree(_DATA_DIR, ignore_errors=True)
