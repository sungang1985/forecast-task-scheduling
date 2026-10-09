package com.forecast.task.data.cache;

import com.forecast.task.data.DataSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.types.Expiration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Redis 二级缓存：多实例共享已加载的分段，新实例/重启后无需重新读库。
 * Redis 任何异常都降级为缓存未命中，绝不影响预测任务本身。
 */
@Slf4j
public class RedisSegmentStore {

    private final RedisTemplate<String, byte[]> redis;
    private final String prefix;
    private final String evictChannel;

    public RedisSegmentStore(RedisTemplate<String, byte[]> redis, String prefix, String evictChannel) {
        this.redis = redis;
        this.prefix = prefix;
        this.evictChannel = evictChannel;
    }

    public Map<SegmentKey, DataSegment> multiGet(List<SegmentKey> keys) {
        Map<SegmentKey, DataSegment> found = new HashMap<>();
        if (keys.isEmpty()) {
            return found;
        }
        try {
            List<byte[]> values = redis.opsForValue().multiGet(keys.stream().map(k -> k.redisKey(prefix)).toList());
            if (values == null) {
                return found;
            }
            for (int i = 0; i < keys.size(); i++) {
                byte[] value = values.get(i);
                if (value != null) {
                    try {
                        found.put(keys.get(i), SegmentCodec.decode(value));
                    } catch (RuntimeException e) {
                        log.warn("Redis 分段数据损坏，按未命中处理: {}", keys.get(i));
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("读取 Redis 二级缓存失败，降级读库: {}", e.getMessage());
        }
        return found;
    }

    public void putAll(Map<SegmentKey, DataSegment> segments, Function<SegmentKey, Duration> ttl) {
        if (segments.isEmpty()) {
            return;
        }
        try {
            redis.executePipelined((RedisCallback<Object>) connection -> {
                for (Map.Entry<SegmentKey, DataSegment> e : segments.entrySet()) {
                    connection.stringCommands().set(
                            e.getKey().redisKey(prefix).getBytes(StandardCharsets.UTF_8),
                            SegmentCodec.encode(e.getValue()),
                            Expiration.from(ttl.apply(e.getKey())),
                            RedisStringCommands.SetOption.upsert());
                }
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("写入 Redis 二级缓存失败: {}", e.getMessage());
        }
    }

    /**
     * 删除某数据源与 [from, to) 有交集的分段。
     */
    public int evict(String sourceCode, LocalDate from, LocalDate to) {
        String pattern = prefix + (sourceCode == null ? "*" : sourceCode + ":*");
        List<String> toDelete = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(1000).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                SegmentKey parsed = parse(key);
                if (parsed != null && parsed.overlaps(from, to)) {
                    toDelete.add(key);
                }
            }
            if (!toDelete.isEmpty()) {
                redis.delete(toDelete);
            }
        } catch (RuntimeException e) {
            log.warn("清理 Redis 二级缓存失败: {}", e.getMessage());
        }
        return toDelete.size();
    }

    public void publishEviction(String message) {
        try {
            redis.convertAndSend(evictChannel, message.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            log.warn("广播缓存失效消息失败: {}", e.getMessage());
        }
    }

    private SegmentKey parse(String redisKey) {
        // prefix + source:unitStart:unitEnd:seriesKey
        String body = redisKey.substring(prefix.length());
        String[] parts = body.split(":", 4);
        if (parts.length < 4) {
            return null;
        }
        try {
            return new SegmentKey(parts[0], parts[3], LocalDate.parse(parts[1]), LocalDate.parse(parts[2]));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
