package com.forecast.task.data.cache;

import com.forecast.task.config.ForecastProperties;
import com.forecast.task.data.DataSegment;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * 预训练数据两级分段缓存：L1 Caffeine（本地，按行数加权淘汰） + L2 Redis（可选，多实例共享）。
 * <ul>
 *     <li>按“数据源 + 预测对象 + 时间分段”缓存，任意时间窗口的请求都能复用已缓存分段；</li>
 *     <li>AsyncCache 合并同一分段的并发加载，避免缓存击穿时多个任务同时读库；</li>
 *     <li>近期分段（可能仍有数据补录）使用短 TTL，历史分段使用长 TTL；</li>
 *     <li>缺失分段批量（IN 查询）回源，减少数据库往返。</li>
 * </ul>
 */
@Slf4j
@Component
public class SegmentCache {

    private final ForecastProperties.Cache cfg;
    private final RedisSegmentStore remote;
    private final AsyncCache<SegmentKey, DataSegment> local;
    private final Counter remoteHits;
    private final Counter remoteMisses;
    private final Counter dbLoadedSegments;

    public SegmentCache(ForecastProperties properties, ObjectProvider<RedisSegmentStore> remoteStore, MeterRegistry registry) {
        this.cfg = properties.getCache();
        this.remote = cfg.isRedisEnabled() ? remoteStore.getIfAvailable() : null;
        this.local = Caffeine.newBuilder()
                .maximumWeight(cfg.getLocalMaxRows())
                .<SegmentKey, DataSegment>weigher((k, v) -> Math.max(1, v.rowCount()))
                .expireAfter(new Expiry<SegmentKey, DataSegment>() {
                    @Override
                    public long expireAfterCreate(SegmentKey key, DataSegment value, long currentTime) {
                        return ttlOf(key).toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(SegmentKey key, DataSegment value, long currentTime, long currentDuration) {
                        return ttlOf(key).toNanos();
                    }

                    @Override
                    public long expireAfterRead(SegmentKey key, DataSegment value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                // 在调用线程中执行回源加载，并发请求同一分段时共享同一个 future
                .executor(Runnable::run)
                .recordStats()
                .buildAsync();
        CaffeineCacheMetrics.monitor(registry, local.synchronous(), "forecast_data_segment");
        this.remoteHits = Counter.builder("forecast.cache.remote").tag("result", "hit").register(registry);
        this.remoteMisses = Counter.builder("forecast.cache.remote").tag("result", "miss").register(registry);
        this.dbLoadedSegments = Counter.builder("forecast.cache.db.loads").register(registry);
    }

    /**
     * 批量获取分段：L1 未命中 -> L2 -> 数据库（dbLoader 必须为每个请求的 key 返回结果，空数据返回空分段）。
     */
    public Map<SegmentKey, DataSegment> getAll(List<SegmentKey> keys,
                                               Function<Set<SegmentKey>, Map<SegmentKey, DataSegment>> dbLoader) {
        try {
            return local.getAll(keys, missing -> loadMissing(Set.copyOf(missing), dbLoader)).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    private Map<SegmentKey, DataSegment> loadMissing(Set<SegmentKey> missing,
                                                     Function<Set<SegmentKey>, Map<SegmentKey, DataSegment>> dbLoader) {
        Map<SegmentKey, DataSegment> result = new HashMap<>();
        if (remote != null) {
            Map<SegmentKey, DataSegment> fromRemote = remote.multiGet(List.copyOf(missing));
            result.putAll(fromRemote);
            remoteHits.increment(fromRemote.size());
            remoteMisses.increment(missing.size() - fromRemote.size());
        }
        Set<SegmentKey> toLoad = new java.util.HashSet<>(missing);
        toLoad.removeAll(result.keySet());
        if (!toLoad.isEmpty()) {
            Map<SegmentKey, DataSegment> loaded = dbLoader.apply(toLoad);
            dbLoadedSegments.increment(loaded.size());
            result.putAll(loaded);
            if (remote != null) {
                remote.putAll(loaded, this::ttlOf);
            }
        }
        return result;
    }

    /**
     * 失效指定数据源的分段（数据补录/修正后调用），并广播给其他实例。
     *
     * @return 本地失效的分段数
     */
    public int evict(String sourceCode, LocalDate from, LocalDate to) {
        int count = evictLocal(sourceCode, from, to);
        if (remote != null) {
            int remoteCount = remote.evict(sourceCode, from, to);
            remote.publishEviction(EvictionMessage.encode(sourceCode, from, to));
            log.info("已失效缓存分段 source={} [{}, {}) local={} remote={}", sourceCode, from, to, count, remoteCount);
        } else {
            log.info("已失效缓存分段 source={} [{}, {}) local={}", sourceCode, from, to, count);
        }
        return count;
    }

    public int evictLocal(String sourceCode, LocalDate from, LocalDate to) {
        int[] counter = {0};
        local.synchronous().asMap().keySet().removeIf(k -> {
            boolean match = (sourceCode == null || k.sourceCode().equals(sourceCode)) && k.overlaps(from, to);
            if (match) {
                counter[0]++;
            }
            return match;
        });
        return counter[0];
    }

    public Map<String, Object> stats() {
        CacheStats s = local.synchronous().stats();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", cfg.isEnabled());
        stats.put("redisEnabled", remote != null);
        stats.put("localSegments", local.synchronous().estimatedSize());
        local.synchronous().policy().eviction()
                .ifPresent(ev -> stats.put("localWeightedRows", ev.weightedSize().orElse(-1)));
        stats.put("localMaxRows", cfg.getLocalMaxRows());
        stats.put("localHitCount", s.hitCount());
        stats.put("localMissCount", s.missCount());
        stats.put("localHitRate", s.hitRate());
        stats.put("localEvictionCount", s.evictionCount());
        stats.put("remoteHitCount", (long) remoteHits.count());
        stats.put("remoteMissCount", (long) remoteMisses.count());
        stats.put("dbLoadedSegments", (long) dbLoadedSegments.count());
        return stats;
    }

    private Duration ttlOf(SegmentKey key) {
        LocalDate hotBoundary = LocalDate.now().minusDays(Math.max(0, cfg.getHotWindow().toDays()));
        return key.unitEnd().isAfter(hotBoundary) ? cfg.getHotTtl() : cfg.getHistoryTtl();
    }

    /**
     * 缓存失效广播消息：sourceCode|from|to。
     */
    public static final class EvictionMessage {
        private EvictionMessage() {
        }

        public static String encode(String source, LocalDate from, LocalDate to) {
            return nullToDash(source) + "|" + nullToDash(from) + "|" + nullToDash(to);
        }

        public static Object[] decode(String message) {
            String[] parts = message.split("\\|", -1);
            return new Object[]{dashToNull(parts[0]),
                    dashToNull(parts[1]) == null ? null : LocalDate.parse(parts[1]),
                    dashToNull(parts[2]) == null ? null : LocalDate.parse(parts[2])};
        }

        private static String nullToDash(Object o) {
            return o == null ? "-" : o.toString();
        }

        private static String dashToNull(String s) {
            return "-".equals(s) ? null : s;
        }
    }
}
