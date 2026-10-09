package com.forecast.task.engine;

import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import com.forecast.task.config.ExecutorConfig;
import com.forecast.task.config.ForecastProperties;
import com.forecast.task.data.DataSourceRegistry;
import com.forecast.task.data.TrainingDataService;
import com.forecast.task.engine.dto.EngineDtos.CompleteDatasetRequest;
import com.forecast.task.engine.dto.EngineDtos.CompleteDatasetResponse;
import com.forecast.task.engine.dto.EngineDtos.CreateDatasetRequest;
import com.forecast.task.engine.dto.EngineDtos.PartSpec;
import com.forecast.task.engine.dto.EngineDtos.PartSummary;
import com.forecast.task.pipeline.PipelineContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 预训练数据分批传输：
 * <ol>
 *     <li>创建数据集会话；</li>
 *     <li>逐段读取（命中缓存）→ 切分为固定行数的分片 → gzip + CRC32 → 有界并发上传（信号量背压，内存可控）；</li>
 *     <li>分片上传幂等，失败按指数退避重试；</li>
 *     <li>结束上传时服务端校验分片完整性与行数，缺片则判定失败。</li>
 * </ol>
 */
@Slf4j
@Service
public class DatasetTransferService {

    public static final String PRIMARY_PART = "primary";
    public static final String COL_TS = "ts";
    public static final String COL_SERIES_KEY = "series_key";

    private final PythonEngineClient client;
    private final TrainingDataService dataService;
    private final DataSourceRegistry dataSourceRegistry;
    private final ForecastProperties properties;
    private final AsyncTaskExecutor transferExecutor;
    private final Counter bytesCounter;
    private final Counter chunkCounter;

    public DatasetTransferService(PythonEngineClient client, TrainingDataService dataService,
                                  DataSourceRegistry dataSourceRegistry, ForecastProperties properties,
                                  @Qualifier(ExecutorConfig.TRANSFER_EXECUTOR) AsyncTaskExecutor transferExecutor,
                                  MeterRegistry meterRegistry) {
        this.client = client;
        this.dataService = dataService;
        this.dataSourceRegistry = dataSourceRegistry;
        this.properties = properties;
        this.transferExecutor = transferExecutor;
        this.bytesCounter = Counter.builder("forecast.transfer.bytes").description("上传至 Python 引擎的字节数")
                .baseUnit("bytes").register(meterRegistry);
        this.chunkCounter = Counter.builder("forecast.transfer.chunks").description("上传分片数").register(meterRegistry);
    }

    /**
     * 一个数据分区（对应一个数据源）。
     */
    public record PartRequest(String name, String role, String sourceCode, Collection<String> seriesKeys,
                              LocalDateTime start, LocalDateTime end, boolean joinOnKey, boolean required) {
    }

    public record TransferResult(String datasetId, Map<String, Long> rows, Map<String, Integer> chunks, long bytes,
                                 long costMs) {
    }

    public TransferResult transfer(PipelineContext ctx, EngineEndpoint endpoint, List<PartRequest> parts) {
        long begin = System.currentTimeMillis();
        Map<String, PartSpec> specs = new LinkedHashMap<>();
        Map<String, List<String>> columnsByPart = new LinkedHashMap<>();
        for (PartRequest part : parts) {
            List<String> columns = new ArrayList<>();
            columns.add(COL_TS);
            columns.add(COL_SERIES_KEY);
            columns.addAll(dataSourceRegistry.require(part.sourceCode()).fieldNames());
            columnsByPart.put(part.name(), columns);
            specs.put(part.name(), new PartSpec(part.sourceCode(), part.role(), columns, part.joinOnKey()));
        }
        String datasetId = client.createDataset(endpoint, new CreateDatasetRequest(ctx.getTaskId(), ctx.getTaskType(),
                ctx.getTaskTypeDef().getFrequency(), specs));
        // 无论成败，任务结束时清理 Python 端临时数据集
        if (properties.getEngine().isCleanupDatasets()) {
            ctx.addCleanup(() -> client.deleteDataset(endpoint, datasetId));
        }

        Map<String, Long> rows = new LinkedHashMap<>();
        Map<String, Integer> chunks = new LinkedHashMap<>();
        long[] bytes = {0};
        for (int i = 0; i < parts.size(); i++) {
            PartRequest part = parts.get(i);
            PartUploadResult result = uploadPart(ctx, endpoint, datasetId, part, columnsByPart.get(part.name()), i, parts.size());
            if (result.rows == 0 && part.required()) {
                throw new BizException(ErrorCode.DATA_EMPTY, String.format("数据源 %s 在 [%s, %s) 内没有数据（预测对象: %s）",
                        part.sourceCode(), part.start(), part.end(),
                        part.seriesKeys() == null || part.seriesKeys().isEmpty() ? "全部" : part.seriesKeys()));
            }
            rows.put(part.name(), result.rows);
            chunks.put(part.name(), result.chunks);
            bytes[0] += result.bytes;
        }

        Map<String, PartSummary> summaries = new LinkedHashMap<>();
        rows.forEach((name, count) -> summaries.put(name, new PartSummary(chunks.get(name), count)));
        CompleteDatasetResponse completed = client.completeDataset(endpoint, datasetId, new CompleteDatasetRequest(summaries));
        if (completed.missingChunks() != null && completed.missingChunks().values().stream().anyMatch(l -> !l.isEmpty())) {
            throw new BizException(ErrorCode.DATA_TRANSFER_FAILED, "数据集分片不完整: " + completed.missingChunks());
        }
        long cost = System.currentTimeMillis() - begin;
        log.info("数据集传输完成 datasetId={} rows={} chunks={} bytes={} cost={}ms", datasetId, rows, chunks, bytes[0], cost);
        return new TransferResult(datasetId, rows, chunks, bytes[0], cost);
    }

    private record PartUploadResult(long rows, int chunks, long bytes) {
    }

    private PartUploadResult uploadPart(PipelineContext ctx, EngineEndpoint endpoint, String datasetId,
                                        PartRequest part, List<String> columns, int partIndex, int partCount) {
        ForecastProperties.Transfer cfg = properties.getTransfer();
        Semaphore permits = new Semaphore(Math.max(1, cfg.getParallelism()));
        List<CompletableFuture<Void>> uploads = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger uploaded = new AtomicInteger();
        long[] bytes = {0};
        Duration window = Duration.between(part.start(), part.end());

        ChunkWriter writer = new ChunkWriter(columns, cfg.getChunkRows(), cfg.isGzip(), chunk -> {
            throwIfFailed(failure);
            acquire(permits, ctx);
            bytes[0] += chunk.body().length;
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                try {
                    if (failure.get() == null) {
                        client.uploadChunk(endpoint, datasetId, part.name(), chunk.seq(), chunk.body(), chunk.gzip(),
                                chunk.rows(), chunk.checksum());
                        uploaded.incrementAndGet();
                        chunkCounter.increment();
                        bytesCounter.increment(chunk.body().length);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    throw t;
                } finally {
                    permits.release();
                }
            }, transferExecutor);
            uploads.add(f);
        });

        try {
            dataService.read(part.sourceCode(), part.seriesKeys(), part.start(), part.end(), slice -> {
                ctx.checkActive();
                throwIfFailed(failure);
                writer.append(slice);
                if (!slice.rows().isEmpty() && !window.isZero()) {
                    LocalDateTime last = slice.rows().get(slice.rows().size() - 1).ts();
                    double fraction = Math.min(1.0, (double) Duration.between(part.start(), last).toMinutes() / window.toMinutes());
                    ctx.reportProgress((int) ((partIndex + fraction) * 95 / partCount),
                            "传输分区 " + part.name() + "，已上传分片 " + uploaded.get());
                }
            });
            int chunkCount = writer.finish();
            CompletableFuture.allOf(uploads.toArray(CompletableFuture[]::new)).join();
            throwIfFailed(failure);
            return new PartUploadResult(writer.getTotalRows(), chunkCount, bytes[0]);
        } catch (CompletionException e) {
            throw wrap(failure.get() != null ? failure.get() : e.getCause());
        } catch (RuntimeException e) {
            // 等待已提交的分片结束，避免任务结束后仍有后台上传
            uploads.forEach(f -> f.exceptionally(t -> null).join());
            throw e;
        }
    }

    private static void acquire(Semaphore permits, PipelineContext ctx) {
        try {
            while (!permits.tryAcquire(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                ctx.checkActive();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.DATA_TRANSFER_FAILED, "分片上传被中断");
        }
    }

    private static void throwIfFailed(AtomicReference<Throwable> failure) {
        Throwable t = failure.get();
        if (t != null) {
            throw wrap(t);
        }
    }

    private static RuntimeException wrap(Throwable t) {
        if (t instanceof EngineCallException ece) {
            return ece;
        }
        if (t instanceof RuntimeException re && !(t instanceof CompletionException)) {
            return re;
        }
        return new BizException(ErrorCode.DATA_TRANSFER_FAILED, "分片上传失败: " + t.getMessage(), t);
    }
}
