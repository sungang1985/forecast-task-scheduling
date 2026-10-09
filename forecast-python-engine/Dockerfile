FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    ENGINE_DATA_DIR=/data \
    ENGINE_PORT=8000 \
    TZ=Asia/Shanghai

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
RUN useradd -r -u 10001 engine && mkdir -p /data && chown engine /data
USER engine
VOLUME ["/data"]
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health').status==200 else 1)"

# 单进程多线程：任务状态在进程内存 + 磁盘；扩容请增加容器副本并挂载共享 /data
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${ENGINE_PORT} --timeout-keep-alive 75"]
