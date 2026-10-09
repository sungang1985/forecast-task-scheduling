package com.forecast.task.data.cache;

import com.forecast.task.data.DataRow;
import com.forecast.task.data.DataSegment;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 分段数据二进制编解码（gzip），用于 Redis 二级缓存。
 * 相比 JSON 体积更小、无精度损失，且可原样保留 NaN 缺失值。
 */
public final class SegmentCodec {

    private static final int VERSION = 1;

    private SegmentCodec() {
    }

    public static byte[] encode(DataSegment segment) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(256, segment.rowCount() * 12));
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes, 8192))) {
            out.writeByte(VERSION);
            out.writeShort(segment.fields().size());
            for (String field : segment.fields()) {
                out.writeUTF(field);
            }
            out.writeInt(segment.series().size());
            for (Map.Entry<String, List<DataRow>> e : segment.series().entrySet()) {
                out.writeUTF(e.getKey());
                out.writeInt(e.getValue().size());
                for (DataRow row : e.getValue()) {
                    out.writeLong(row.ts().toEpochSecond(ZoneOffset.UTC));
                    for (double v : row.values()) {
                        out.writeDouble(v);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static DataSegment decode(byte[] data) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data), 8192))) {
            int version = in.readByte();
            if (version != VERSION) {
                throw new IllegalStateException("不支持的分段编码版本: " + version);
            }
            int fieldCount = in.readShort();
            List<String> fields = new ArrayList<>(fieldCount);
            for (int i = 0; i < fieldCount; i++) {
                fields.add(in.readUTF());
            }
            int seriesCount = in.readInt();
            Map<String, List<DataRow>> series = new HashMap<>(seriesCount * 2);
            for (int s = 0; s < seriesCount; s++) {
                String key = in.readUTF();
                int rows = in.readInt();
                List<DataRow> list = new ArrayList<>(rows);
                for (int r = 0; r < rows; r++) {
                    LocalDateTime ts = LocalDateTime.ofEpochSecond(in.readLong(), 0, ZoneOffset.UTC);
                    double[] values = new double[fieldCount];
                    for (int f = 0; f < fieldCount; f++) {
                        values[f] = in.readDouble();
                    }
                    list.add(new DataRow(ts, values));
                }
                series.put(key, list);
            }
            return new DataSegment(fields, series);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
