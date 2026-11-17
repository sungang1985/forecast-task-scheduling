package com.forecast.task.config;

import com.forecast.task.data.cache.RedisSegmentStore;
import com.forecast.task.data.cache.SegmentCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Redis 二级缓存相关配置，仅在 forecast.cache.redis-enabled=true 时生效。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "forecast.cache", name = "redis-enabled", havingValue = "true")
public class RedisCacheConfig {

    @Bean
    public RedisTemplate<String, byte[]> segmentRedisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(RedisSerializer.byteArray());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedisSegmentStore redisSegmentStore(RedisTemplate<String, byte[]> segmentRedisTemplate,
                                               ForecastProperties properties) {
        ForecastProperties.Cache cfg = properties.getCache();
        return new RedisSegmentStore(segmentRedisTemplate, cfg.getRedisKeyPrefix(), cfg.getEvictChannel());
    }

    /**
     * 订阅缓存失效广播，同步清理本实例 L1 缓存。
     */
    @Bean
    public RedisMessageListenerContainer segmentEvictionListener(RedisConnectionFactory factory,
                                                                 ForecastProperties properties,
                                                                 @Lazy SegmentCache segmentCache) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener((message, pattern) -> {
            try {
                Object[] parsed = SegmentCache.EvictionMessage.decode(new String(message.getBody(), StandardCharsets.UTF_8));
                segmentCache.evictLocal((String) parsed[0], (LocalDate) parsed[1], (LocalDate) parsed[2]);
            } catch (RuntimeException e) {
                log.warn("处理缓存失效广播失败: {}", e.getMessage());
            }
        }, new ChannelTopic(properties.getCache().getEvictChannel()));
        return container;
    }
}
