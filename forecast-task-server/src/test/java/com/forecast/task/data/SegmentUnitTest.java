package com.forecast.task.data;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentUnitTest {

    @Test
    void monthSplitAlignsToMonthStartAndCoversWindow() {
        List<LocalDate> units = SegmentUnit.MONTH.split(LocalDateTime.of(2026, 1, 15, 0, 0),
                LocalDateTime.of(2026, 3, 1, 0, 15));
        assertThat(units).containsExactly(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1));
    }

    @Test
    void endOnBoundaryIsExclusive() {
        List<LocalDate> units = SegmentUnit.DAY.split(LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 3, 0, 0));
        assertThat(units).containsExactly(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2));
    }

    @Test
    void emptyWindowYieldsNoUnits() {
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 0, 0);
        assertThat(SegmentUnit.MONTH.split(t, t)).isEmpty();
    }
}
