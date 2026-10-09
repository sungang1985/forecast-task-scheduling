package com.forecast.task.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forecast.task.data.DataRow;
import com.forecast.task.data.SeriesSlice;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void splitsRowsIntoFixedSizeGzipChunksWithChecksum() throws Exception {
        List<ChunkWriter.Chunk> chunks = new ArrayList<>();
        ChunkWriter writer = new ChunkWriter(List.of("ts", "series_key", "load"), 4, true, chunks::add);
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<DataRow> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(new DataRow(t0.plusMinutes(15L * i), new double[]{i == 3 ? Double.NaN : i * 1.5}));
        }
        writer.append(new SeriesSlice("AREA_01", List.of("load"), rows.subList(0, 6)));
        writer.append(new SeriesSlice("AREA_02", List.of("load"), rows.subList(6, 10)));
        int count = writer.finish();

        assertThat(count).isEqualTo(3);
        assertThat(writer.getTotalRows()).isEqualTo(10);
        assertThat(chunks).extracting(ChunkWriter.Chunk::rows).containsExactly(4, 4, 2);
        assertThat(chunks).extracting(ChunkWriter.Chunk::seq).containsExactly(0, 1, 2);

        ChunkWriter.Chunk first = chunks.get(0);
        CRC32 crc = new CRC32();
        crc.update(first.body());
        assertThat(first.checksum()).isEqualTo(Long.toHexString(crc.getValue()));

        JsonNode json = mapper.readTree(new GZIPInputStream(new ByteArrayInputStream(first.body())));
        assertThat(json.get("columns").toString()).isEqualTo("[\"ts\",\"series_key\",\"load\"]");
        assertThat(json.get("rows").get(0).toString()).isEqualTo("[\"2026-01-01T00:00:00\",\"AREA_01\",0.0]");
        // NaN 编码为 null
        assertThat(json.get("rows").get(3).get(2).isNull()).isTrue();

        JsonNode last = mapper.readTree(new GZIPInputStream(new ByteArrayInputStream(chunks.get(2).body())));
        assertThat(last.get("rows").get(0).get(1).asText()).isEqualTo("AREA_02");
    }

    @Test
    void emptyInputProducesNoChunks() {
        List<ChunkWriter.Chunk> chunks = new ArrayList<>();
        ChunkWriter writer = new ChunkWriter(List.of("ts", "series_key", "v"), 10, false, chunks::add);
        assertThat(writer.finish()).isZero();
        assertThat(chunks).isEmpty();
    }
}
