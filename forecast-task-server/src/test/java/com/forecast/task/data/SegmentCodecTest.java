package com.forecast.task.data;

import com.forecast.task.data.cache.SegmentCodec;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentCodecTest {

    @Test
    void roundTripPreservesValuesAndNaN() {
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 0, 0);
        DataSegment segment = new DataSegment(List.of("price", "volume"), Map.of(
                "MARKET_A", List.of(new DataRow(t0, new double[]{-12.5, Double.NaN}),
                        new DataRow(t0.plusMinutes(15), new double[]{300.125, 88.0})),
                "MARKET_B", List.of()));

        byte[] encoded = SegmentCodec.encode(segment);
        DataSegment decoded = SegmentCodec.decode(encoded);

        assertThat(decoded.fields()).containsExactly("price", "volume");
        assertThat(decoded.series()).containsOnlyKeys("MARKET_A", "MARKET_B");
        List<DataRow> rows = decoded.series().get("MARKET_A");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).ts()).isEqualTo(t0);
        assertThat(rows.get(0).values()[0]).isEqualTo(-12.5);
        assertThat(rows.get(0).values()[1]).isNaN();
        assertThat(rows.get(1).values()).containsExactly(300.125, 88.0);
        assertThat(decoded.rowCount()).isEqualTo(2);
    }
}
