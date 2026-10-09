package com.forecast.task.data;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import com.forecast.task.data.layout.LayoutHandler;
import com.forecast.task.domain.entity.DataSourceConfig;
import com.forecast.task.mapper.DataSourceConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源注册表：从 forecast_data_source 加载并校验数据源定义，定期刷新，
 * 新接入业务表只需新增一条配置，无需发版。
 */
@Slf4j
@Component
public class DataSourceRegistry {

    private final DataSourceConfigMapper mapper;
    private final Map<DatasetLayout, LayoutHandler> handlers = new EnumMap<>(DatasetLayout.class);
    private volatile Map<String, DataSourceDefinition> definitions = Collections.emptyMap();

    public DataSourceRegistry(DataSourceConfigMapper mapper, List<LayoutHandler> layoutHandlers) {
        this.mapper = mapper;
        layoutHandlers.forEach(h -> handlers.put(h.layout(), h));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        reload();
    }

    @Scheduled(fixedDelayString = "${forecast.data-source-refresh-interval:60000}", initialDelay = 60000)
    public void scheduledReload() {
        reload();
    }

    /**
     * 重新加载全部启用的数据源；非法配置记录日志并跳过，不影响其他数据源。
     */
    public synchronized void reload() {
        List<DataSourceConfig> configs = mapper.selectList(Wrappers.<DataSourceConfig>lambdaQuery()
                .eq(DataSourceConfig::getEnabled, true));
        Map<String, DataSourceDefinition> loaded = new HashMap<>();
        for (DataSourceConfig cfg : configs) {
            try {
                loaded.put(cfg.getSourceCode(), DataSourceDefinition.from(cfg));
            } catch (BizException e) {
                log.error("数据源配置 [{}] 非法，已忽略: {}", cfg.getSourceCode(), e.getMessage());
            }
        }
        definitions = Collections.unmodifiableMap(loaded);
        log.debug("已加载数据源 {} 个: {}", loaded.size(), loaded.keySet());
    }

    public DataSourceDefinition require(String sourceCode) {
        DataSourceDefinition def = definitions.get(sourceCode);
        if (def == null) {
            // 未命中时即时刷新一次，兼容刚新增的数据源
            reload();
            def = definitions.get(sourceCode);
        }
        if (def == null) {
            throw new BizException(ErrorCode.DATA_SOURCE_NOT_FOUND, "数据源不存在或未启用: " + sourceCode);
        }
        return def;
    }

    public LayoutHandler handler(DatasetLayout layout) {
        LayoutHandler handler = handlers.get(layout);
        if (handler == null) {
            throw new BizException(ErrorCode.DATA_SOURCE_INVALID, "未注册表结构适配器: " + layout);
        }
        return handler;
    }

    public Collection<DataSourceDefinition> all() {
        return definitions.values();
    }
}
