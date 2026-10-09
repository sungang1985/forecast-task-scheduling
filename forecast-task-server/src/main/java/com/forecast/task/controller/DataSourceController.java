package com.forecast.task.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import com.forecast.common.model.R;
import com.forecast.task.data.DataRow;
import com.forecast.task.data.DataSourceDefinition;
import com.forecast.task.data.DataSourceRegistry;
import com.forecast.task.data.SeriesSlice;
import com.forecast.task.data.TrainingDataService;
import com.forecast.task.data.cache.SegmentCache;
import com.forecast.task.domain.entity.DataSourceConfig;
import com.forecast.task.mapper.DataSourceConfigMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预训练数据源管理：不同表结构的业务表通过配置接入，并可预览统一映射后的数据。
 */
@Tag(name = "数据源", description = "预训练数据源（不同表结构）配置与数据预览")
@RestController
@RequestMapping("/api/v1/data-sources")
public class DataSourceController {

    private final DataSourceConfigMapper mapper;
    private final DataSourceRegistry registry;
    private final TrainingDataService dataService;
    private final SegmentCache segmentCache;

    public DataSourceController(DataSourceConfigMapper mapper, DataSourceRegistry registry,
                                TrainingDataService dataService, SegmentCache segmentCache) {
        this.mapper = mapper;
        this.registry = registry;
        this.dataService = dataService;
        this.segmentCache = segmentCache;
    }

    @Operation(summary = "数据源列表")
    @GetMapping
    public R<List<DataSourceConfig>> list() {
        return R.ok(mapper.selectList(Wrappers.<DataSourceConfig>lambdaQuery().orderByAsc(DataSourceConfig::getSourceCode)));
    }

    @Operation(summary = "新增数据源", description = "保存前校验表名/列名合法性与布局必填项")
    @PostMapping
    public R<DataSourceConfig> create(@Valid @RequestBody DataSourceConfig config) {
        config.setId(null);
        DataSourceDefinition.from(config);
        if (config.getEnabled() == null) {
            config.setEnabled(true);
        }
        try {
            mapper.insert(config);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "数据源编码已存在: " + config.getSourceCode());
        }
        registry.reload();
        return R.ok(config);
    }

    @Operation(summary = "修改数据源（同时失效该数据源缓存）")
    @PutMapping("/{sourceCode}")
    public R<DataSourceConfig> update(@PathVariable String sourceCode, @Valid @RequestBody DataSourceConfig config) {
        DataSourceConfig existing = requireConfig(sourceCode);
        config.setId(existing.getId());
        config.setSourceCode(sourceCode);
        DataSourceDefinition.from(config);
        mapper.updateById(config);
        registry.reload();
        segmentCache.evict(sourceCode, null, null);
        return R.ok(mapper.selectById(existing.getId()));
    }

    @Operation(summary = "删除数据源")
    @DeleteMapping("/{sourceCode}")
    public R<Void> delete(@PathVariable String sourceCode) {
        DataSourceConfig existing = requireConfig(sourceCode);
        mapper.deleteById(existing.getId());
        registry.reload();
        segmentCache.evict(sourceCode, null, null);
        return R.ok();
    }

    @Operation(summary = "预览统一映射后的数据（验证表结构配置）")
    @GetMapping("/{sourceCode}/preview")
    public R<Map<String, Object>> preview(@PathVariable String sourceCode,
                                          @RequestParam(required = false) List<String> seriesKeys,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
                                          @RequestParam(defaultValue = "200") int limit) {
        DataSourceDefinition def = registry.require(sourceCode);
        List<SeriesSlice> slices = dataService.readAll(sourceCode, seriesKeys, start, end, Math.min(limit, 5000));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SeriesSlice slice : slices) {
            for (DataRow row : slice.rows()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("ts", row.ts());
                item.put("series_key", slice.seriesKey());
                for (int i = 0; i < slice.fields().size(); i++) {
                    double v = row.values()[i];
                    item.put(slice.fields().get(i), Double.isNaN(v) ? null : v);
                }
                rows.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("definition", def);
        result.put("rows", rows);
        return R.ok(result);
    }

    private DataSourceConfig requireConfig(String sourceCode) {
        DataSourceConfig config = mapper.selectOne(Wrappers.<DataSourceConfig>lambdaQuery()
                .eq(DataSourceConfig::getSourceCode, sourceCode));
        if (config == null) {
            throw new BizException(ErrorCode.DATA_SOURCE_NOT_FOUND, "数据源不存在: " + sourceCode);
        }
        return config;
    }
}
