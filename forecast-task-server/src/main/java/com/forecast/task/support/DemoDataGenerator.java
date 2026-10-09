package com.forecast.task.support;

import com.forecast.task.config.ForecastProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.StringJoiner;

/**
 * 演示数据生成器（仅 forecast.demo.enabled=true 时生效）：
 * 为三张不同结构的演示表生成具有日内曲线、周特性、温度敏感性的合成数据，并每日自动补齐，
 * 便于在无真实数据时完整体验 预处理 → 训练 → 预测 → 入库 的全流程。
 * 时间参数一律使用 java.time 类型，避免驱动按 JVM/数据库时区差换算。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "forecast.demo", name = "enabled", havingValue = "true")
public class DemoDataGenerator {

    static final String[] AREAS = {"AREA_01", "AREA_02", "AREA_03"};
    private static final double[] AREA_BASE = {800, 1200, 600};
    static final String MARKET = "MARKET_A";
    private static final int POINTS = 96;

    private final JdbcTemplate jdbc;
    private final ForecastProperties properties;

    public DemoDataGenerator(JdbcTemplate jdbc, ForecastProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        fill();
    }

    @Scheduled(cron = "0 5 0 * * *")
    public void daily() {
        fill();
    }

    /**
     * 补齐数据：负荷/价格到昨天为止，气象到后天（含预报）。幂等。
     */
    public synchronized void fill() {
        LocalDate today = LocalDate.now();
        LocalDate earliest = today.minusDays(properties.getDemo().getHistoryDays());
        try {
            LocalDate loadFrom = nextDate("SELECT MAX(load_date) FROM ods_load_curve_96", earliest);
            LocalDate priceFrom = nextDate("SELECT MAX(trade_date) FROM ods_market_price", earliest);
            LocalDate weatherFrom = nextDate("SELECT MAX(CAST(record_time AS DATE)) FROM ods_weather_hourly", earliest);
            int weather = insertWeather(weatherFrom, today.plusDays(3));
            int load = insertLoad(loadFrom, today);
            int price = insertPrice(priceFrom, today);
            if (weather + load + price > 0) {
                log.info("演示数据已补齐：气象 {} 行，负荷 {} 天·区域，量价 {} 行", weather, load, price);
            }
        } catch (RuntimeException e) {
            log.warn("演示数据生成跳过（演示表不存在？请确认 flyway 包含 classpath:db/demo）: {}", e.getMessage());
        }
    }

    private LocalDate nextDate(String sql, LocalDate earliest) {
        LocalDate last = jdbc.queryForObject(sql, LocalDate.class);
        if (last == null) {
            return earliest;
        }
        return last.plusDays(1).isAfter(earliest) ? last.plusDays(1) : earliest;
    }

    /** 温度：季节周期 + 日周期 + 区域偏移 + 噪声 */
    static double temperature(int areaIndex, LocalDateTime t) {
        double season = 18 + 10 * Math.sin(2 * Math.PI * (t.getDayOfYear() - 105) / 365.0);
        double hour = t.getHour() + t.getMinute() / 60.0;
        double daily = 5 * Math.sin(2 * Math.PI * (hour - 9) / 24.0);
        Random r = new Random(t.toLocalDate().toEpochDay() * 31 + areaIndex * 7L + t.getHour());
        return season + daily + areaIndex * 1.5 + r.nextGaussian() * 1.2;
    }

    /** 负荷：日内双峰曲线 × 周特性 + 温度敏感（制冷/采暖） + 噪声 */
    static double load(int areaIndex, LocalDateTime t) {
        double hour = t.getHour() + t.getMinute() / 60.0;
        double shape = 0.62
                + 0.25 * Math.exp(-Math.pow(hour - 10.5, 2) / 6)
                + 0.32 * Math.exp(-Math.pow(hour - 19.5, 2) / 5)
                + 0.08 * Math.sin(Math.PI * hour / 24);
        DayOfWeek dow = t.getDayOfWeek();
        double week = dow == DayOfWeek.SATURDAY ? 0.86 : dow == DayOfWeek.SUNDAY ? 0.82 : 1.0;
        double temp = temperature(areaIndex, t);
        double weather = 1 + 0.018 * Math.max(0, temp - 24) + 0.012 * Math.max(0, 10 - temp);
        Random r = new Random(t.toLocalDate().toEpochDay() * 131 + areaIndex * 17L + (long) (hour * 4));
        return AREA_BASE[areaIndex] * shape * week * weather * (1 + r.nextGaussian() * 0.015);
    }

    private int insertWeather(LocalDate from, LocalDate toExclusive) {
        List<Object[]> rows = new ArrayList<>();
        for (LocalDate d = from; d.isBefore(toExclusive); d = d.plusDays(1)) {
            for (int a = 0; a < AREAS.length; a++) {
                for (int h = 0; h < 24; h++) {
                    LocalDateTime t = d.atTime(h, 0);
                    Random r = new Random(d.toEpochDay() * 13 + a * 5L + h);
                    rows.add(new Object[]{AREAS[a], t, round(temperature(a, t)),
                            round(Math.max(15, Math.min(98, 65 - 1.2 * (temperature(a, t) - 18) + r.nextGaussian() * 6)))});
                }
            }
        }
        batch("INSERT INTO ods_weather_hourly (area_code, record_time, temperature, humidity) VALUES (?, ?, ?, ?)", rows);
        return rows.size();
    }

    private int insertLoad(LocalDate from, LocalDate toExclusive) {
        StringJoiner cols = new StringJoiner(", ");
        StringJoiner marks = new StringJoiner(", ");
        for (int p = 1; p <= POINTS; p++) {
            cols.add(String.format("p%03d", p));
            marks.add("?");
        }
        String sql = "INSERT INTO ods_load_curve_96 (area_code, load_date, " + cols + ") VALUES (?, ?, " + marks + ")";
        List<Object[]> rows = new ArrayList<>();
        for (LocalDate d = from; d.isBefore(toExclusive); d = d.plusDays(1)) {
            for (int a = 0; a < AREAS.length; a++) {
                Object[] row = new Object[POINTS + 2];
                row[0] = AREAS[a];
                row[1] = d;
                for (int p = 0; p < POINTS; p++) {
                    row[p + 2] = round(load(a, d.atStartOfDay().plusMinutes(15L * p)));
                }
                // 模拟少量缺测点，供预处理演示缺失值填补
                if ((d.toEpochDay() + a) % 23 == 0) {
                    row[2 + 40] = null;
                    row[2 + 41] = null;
                }
                rows.add(row);
            }
        }
        batch(sql, rows);
        return rows.size();
    }

    private int insertPrice(LocalDate from, LocalDate toExclusive) {
        List<Object[]> rows = new ArrayList<>();
        for (LocalDate d = from; d.isBefore(toExclusive); d = d.plusDays(1)) {
            for (int p = 0; p < POINTS; p++) {
                LocalDateTime t = d.atStartOfDay().plusMinutes(15L * p);
                double total = 0;
                for (int a = 0; a < AREAS.length; a++) {
                    total += load(a, t);
                }
                Random r = new Random(d.toEpochDay() * 977 + p);
                double price = 120 + 0.16 * total + r.nextGaussian() * 18;
                if (r.nextDouble() < 0.004) {
                    // 偶发尖峰价格
                    price *= 2.5;
                }
                double volume = total * 0.25 * (0.92 + r.nextDouble() * 0.08);
                rows.add(new Object[]{MARKET, d, p + 1, "DA", round(price), round(volume)});
                rows.add(new Object[]{MARKET, d, p + 1, "RT", round(price * (0.9 + r.nextDouble() * 0.2)),
                        round(volume * (0.95 + r.nextDouble() * 0.1))});
            }
        }
        batch("INSERT INTO ods_market_price (market_code, trade_date, period_no, price_type, clearing_price, clearing_volume) "
                + "VALUES (?, ?, ?, ?, ?, ?)", rows);
        return rows.size();
    }

    private void batch(String sql, List<Object[]> rows) {
        for (int i = 0; i < rows.size(); i += 2000) {
            jdbc.batchUpdate(sql, rows.subList(i, Math.min(rows.size(), i + 2000)));
        }
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
