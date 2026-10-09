package com.forecast.task.data;

import com.forecast.task.config.ForecastProperties;
import com.forecast.task.data.cache.SegmentCache;
import com.forecast.task.data.cache.SegmentKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * 预训练数据读取服务。
 * <p>
 * 按数据源的分段粒度逐段读取（优先命中缓存），每段裁剪到请求窗口后流式回调给调用方，
 * 内存占用与分段大小相关而与总窗口长度无关，配合分批传输可支撑超大训练集。
 */
@Slf4j
@Service
public class TrainingDataService {

    private static final Comparator<DataRow> BY_TIME = Comparator.comparing(DataRow::ts);

    private final DataSourceRegistry registry;
    private final SegmentCache segmentCache;
    private final ForecastProperties properties;
    private final Counter dbRows;

    public TrainingDataService(DataSourceRegistry registry, SegmentCache segmentCache,
                               ForecastProperties properties, MeterRegistry meterRegistry) {
        this.registry = registry;
        this.segmentCache = segmentCache;
        this.properties = properties;
        this.dbRows = Counter.builder("forecast.data.db.rows").description("从数据库读取的预训练数据行数")
                .register(meterRegistry);
    }

    /**
     * 流式读取 [start, end) 的数据。
     *
     * @param seriesKeys 预测对象，为空表示全部
     * @param consumer   每个“分段 × 预测对象”回调一次，行按时间升序
     * @return 输出的总行数
     */
    public long read(String sourceCode, Collection<String> seriesKeys, LocalDateTime start, LocalDateTime end,
                     Consumer<SeriesSlice> consumer) {
        DataSourceDefinition def = registry.require(sourceCode);
        List<String> keys = normalizeKeys(def, seriesKeys);
        boolean useCache = properties.getCache().isEnabled() && def.cacheEnabled();
        long total = 0;

        for (LocalDate unitStart : def.segmentUnit().split(start, end)) {
            LocalDate unitEnd = def.segmentUnit().next(unitStart);
            List<SegmentKey> segmentKeys = keys.stream()
                    .map(k -> new SegmentKey(def.sourceCode(), k, unitStart, unitEnd))
                    .toList();
            Map<SegmentKey, DataSegment> segments = useCache
                    ? segmentCache.getAll(segmentKeys, missing -> loadFromDb(def, missing))
                    : loadFromDb(def, Set.copyOf(segmentKeys));

            for (SegmentKey key : segmentKeys) {
                DataSegment segment = segments.get(key);
                if (segment == null) {
                    continue;
                }
                // 按 key 排序输出，保证分片内容稳定
                for (String seriesKey : new TreeSet<>(segment.series().keySet())) {
                    List<DataRow> rows = clip(segment.series().get(seriesKey), start, end);
                    if (!rows.isEmpty()) {
                        consumer.accept(new SeriesSlice(seriesKey, segment.fields(), rows));
                        total += rows.size();
                    }
                }
            }
        }
        return total;
    }

    /**
     * 读取并聚合为内存列表（仅用于预览等小数据量场景）。
     */
    public List<SeriesSlice> readAll(String sourceCode, Collection<String> seriesKeys,
                                     LocalDateTime start, LocalDateTime end, int maxRows) {
        List<SeriesSlice> slices = new ArrayList<>();
        int[] count = {0};
        read(sourceCode, seriesKeys, start, end, slice -> {
            if (count[0] >= maxRows) {
                return;
            }
            List<DataRow> rows = slice.rows();
            if (count[0] + rows.size() > maxRows) {
                rows = rows.subList(0, maxRows - count[0]);
            }
            count[0] += rows.size();
            slices.add(new SeriesSlice(slice.seriesKey(), slice.fields(), rows));
        });
        return slices;
    }

    /**
     * 回源加载：同一分段时间单元内的多个预测对象合并为 IN 查询（按 maxKeysPerQuery 分批）。
     * 对每个请求的分段都返回结果（无数据则为空分段），使“空数据”同样被缓存，避免反复穿透。
     */
    private Map<SegmentKey, DataSegment> loadFromDb(DataSourceDefinition def, Set<SegmentKey> missing) {
        Map<SegmentKey, DataSegment> result = new HashMap<>();
        if (missing.isEmpty()) {
            return result;
        }
        List<String> fields = def.fieldNames();
        // 同一批 missing 可能跨分段（理论上同一次调用只有一个分段），按分段分组处理
        Map<LocalDate, List<SegmentKey>> byUnit = new LinkedHashMap<>();
        missing.forEach(k -> byUnit.computeIfAbsent(k.unitStart(), u -> new ArrayList<>()).add(k));

        for (Map.Entry<LocalDate, List<SegmentKey>> unit : byUnit.entrySet()) {
            List<SegmentKey> unitKeys = unit.getValue();
            LocalDateTime from = unit.getKey().atStartOfDay();
            LocalDateTime to = unitKeys.get(0).unitEnd().atStartOfDay();
            long begin = System.currentTimeMillis();

            SegmentKey allKey = unitKeys.stream().filter(SegmentKey::isAllSeries).findFirst().orElse(null);
            if (allKey != null) {
                Map<String, List<DataRow>> data = registry.handler(def.layout()).query(def, null, from, to);
                result.put(allKey, toSegment(fields, data));
            }
            List<String> explicitKeys = unitKeys.stream().filter(k -> !k.isAllSeries()).map(SegmentKey::seriesKey).toList();
            int batch = Math.max(1, properties.getCache().getMaxKeysPerQuery());
            for (int i = 0; i < explicitKeys.size(); i += batch) {
                List<String> part = explicitKeys.subList(i, Math.min(explicitKeys.size(), i + batch));
                Map<String, List<DataRow>> data = registry.handler(def.layout()).query(def, part, from, to);
                for (String seriesKey : part) {
                    List<DataRow> rows = data.getOrDefault(seriesKey, List.of());
                    Map<String, List<DataRow>> single = rows.isEmpty() ? Map.of() : Map.of(seriesKey, rows);
                    result.put(new SegmentKey(def.sourceCode(), seriesKey, unit.getKey(), unitKeys.get(0).unitEnd()),
                            toSegment(fields, single));
                }
            }
            int rows = unitKeys.stream().mapToInt(k -> result.get(k) == null ? 0 : result.get(k).rowCount()).sum();
            dbRows.increment(rows);
            log.debug("回源读取 source={} unit={} keys={} rows={} cost={}ms", def.sourceCode(), unit.getKey(),
                    unitKeys.size(), rows, System.currentTimeMillis() - begin);
        }
        return result;
    }

    private static DataSegment toSegment(List<String> fields, Map<String, List<DataRow>> data) {
        if (data.isEmpty()) {
            return DataSegment.empty(fields);
        }
        Map<String, List<DataRow>> sorted = new HashMap<>(data.size() * 2);
        data.forEach((k, rows) -> {
            List<DataRow> copy = new ArrayList<>(rows);
            copy.sort(BY_TIME);
            sorted.put(k, List.copyOf(copy));
        });
        return new DataSegment(List.copyOf(fields), sorted);
    }

    private static List<DataRow> clip(List<DataRow> rows, LocalDateTime start, LocalDateTime end) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        if (!rows.get(0).ts().isBefore(start) && rows.get(rows.size() - 1).ts().isBefore(end)) {
            return rows;
        }
        int from = lowerBound(rows, start);
        int to = lowerBound(rows, end);
        return from >= to ? List.of() : rows.subList(from, to);
    }

    /** 第一个 ts >= target 的下标 */
    private static int lowerBound(List<DataRow> rows, LocalDateTime target) {
        int lo = 0;
        int hi = rows.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (rows.get(mid).ts().isBefore(target)) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static List<String> normalizeKeys(DataSourceDefinition def, Collection<String> seriesKeys) {
        if (!def.hasKeyColumn() || seriesKeys == null || seriesKeys.isEmpty()) {
            return List.of(SegmentKey.ALL_SERIES);
        }
        return new ArrayList<>(new TreeSet<>(seriesKeys));
    }
}
