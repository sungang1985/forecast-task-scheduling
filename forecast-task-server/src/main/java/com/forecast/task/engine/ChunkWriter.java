package com.forecast.task.engine;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.forecast.task.data.DataRow;
import com.forecast.task.data.SeriesSlice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;

/**
 * 将流式数据切分为固定行数的分片，并以紧凑的列式 JSON（可 gzip）编码：
 * <pre>{"columns":["ts","series_key","load"],"rows":[["2026-01-01T00:00:00","AREA_01",123.4], ...]}</pre>
 * 使用 Jackson 流式写出，单分片内存占用可控。非线程安全。
 */
public class ChunkWriter {

    private static final JsonFactory JSON = new JsonFactory();
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** 分片就绪回调 */
    @FunctionalInterface
    public interface ChunkSink {
        void accept(Chunk chunk);
    }

    public record Chunk(int seq, byte[] body, int rows, String checksum, boolean gzip) {
    }

    private final List<String> columns;
    private final int chunkRows;
    private final boolean gzip;
    private final ChunkSink sink;

    private ByteArrayOutputStream buffer;
    private JsonGenerator generator;
    private int rowsInChunk;
    private int nextSeq;
    private long totalRows;

    /**
     * @param columns 完整列名（ts、series_key 与字段列）
     */
    public ChunkWriter(List<String> columns, int chunkRows, boolean gzip, ChunkSink sink) {
        this.columns = columns;
        this.chunkRows = Math.max(1, chunkRows);
        this.gzip = gzip;
        this.sink = sink;
    }

    public void append(SeriesSlice slice) {
        try {
            for (DataRow row : slice.rows()) {
                if (generator == null) {
                    open();
                }
                generator.writeStartArray();
                generator.writeString(TS.format(row.ts()));
                generator.writeString(slice.seriesKey());
                for (double v : row.values()) {
                    if (Double.isNaN(v) || Double.isInfinite(v)) {
                        generator.writeNull();
                    } else {
                        generator.writeNumber(v);
                    }
                }
                generator.writeEndArray();
                rowsInChunk++;
                totalRows++;
                if (rowsInChunk >= chunkRows) {
                    closeChunk();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 输出剩余数据，返回分片总数 */
    public int finish() {
        try {
            if (generator != null && rowsInChunk > 0) {
                closeChunk();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return nextSeq;
    }

    public long getTotalRows() {
        return totalRows;
    }

    private void open() throws IOException {
        buffer = new ByteArrayOutputStream(Math.min(chunkRows * 48, 8 * 1024 * 1024));
        OutputStream out = gzip ? new GZIPOutputStream(buffer, 16 * 1024) : buffer;
        generator = JSON.createGenerator(out, JsonEncoding.UTF8);
        generator.writeStartObject();
        generator.writeArrayFieldStart("columns");
        for (String c : columns) {
            generator.writeString(c);
        }
        generator.writeEndArray();
        generator.writeArrayFieldStart("rows");
        rowsInChunk = 0;
    }

    private void closeChunk() throws IOException {
        generator.writeEndArray();
        generator.writeEndObject();
        generator.close();
        byte[] body = buffer.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(body);
        Chunk chunk = new Chunk(nextSeq++, body, rowsInChunk, Long.toHexString(crc.getValue()), gzip);
        generator = null;
        buffer = null;
        rowsInChunk = 0;
        sink.accept(chunk);
    }
}
