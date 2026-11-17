# 先在仓库根目录执行：mvn -DskipTests package
FROM eclipse-temurin:17-jre

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -DJM.LOG.PATH=/app/logs/nacos -DJM.SNAPSHOT.PATH=/app/data"

# Nacos 客户端会写日志/快照到 user.home，需要可写的 home 目录
RUN groupadd -r app && useradd -r -m -d /home/app -g app app && mkdir -p /app/logs /app/data && chown -R app:app /app
WORKDIR /app
COPY --chown=app:app target/forecast-gateway.jar app.jar
USER app
EXPOSE 9000

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
