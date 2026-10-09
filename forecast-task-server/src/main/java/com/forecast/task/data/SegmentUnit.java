package com.forecast.task.data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 缓存分段粒度。读取任意时间窗口时，按分段对齐读取并缓存，相邻任务的时间窗口可复用已缓存分段，
 * 从而避免预训练数据被频繁读库。
 */
public enum SegmentUnit {
    DAY {
        @Override
        public LocalDate floor(LocalDate date) {
            return date;
        }

        @Override
        public LocalDate next(LocalDate unitStart) {
            return unitStart.plusDays(1);
        }
    },
    MONTH {
        @Override
        public LocalDate floor(LocalDate date) {
            return date.withDayOfMonth(1);
        }

        @Override
        public LocalDate next(LocalDate unitStart) {
            return unitStart.plusMonths(1);
        }
    };

    public abstract LocalDate floor(LocalDate date);

    public abstract LocalDate next(LocalDate unitStart);

    /**
     * 将 [start, end) 拆分为若干分段起始日期。
     */
    public List<LocalDate> split(LocalDateTime start, LocalDateTime end) {
        List<LocalDate> units = new ArrayList<>();
        if (!start.isBefore(end)) {
            return units;
        }
        LocalDate cursor = floor(start.toLocalDate());
        while (cursor.atStartOfDay().isBefore(end)) {
            units.add(cursor);
            cursor = next(cursor);
        }
        return units;
    }
}
