package com.forecast.task.controller;

import com.forecast.common.model.R;
import com.forecast.task.data.TrainingDataService;
import com.forecast.task.data.cache.SegmentCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预训练数据缓存管理：统计、失效（数据补录/修正后调用）、预热。
 */
@Tag(name = "数据缓存", description = "预训练数据两级缓存统计、失效与预热")
@RestController
@RequestMapping("/api/v1/cache")
public class CacheController {

    private final SegmentCache segmentCache;
    private final TrainingDataService dataService;

    public CacheController(SegmentCache segmentCache, TrainingDataService dataService) {
        this.segmentCache = segmentCache;
        this.dataService = dataService;
    }

    @Operation(summary = "缓存统计")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(segmentCache.stats());
    }

    @Operation(summary = "失效缓存", description = "sourceCode 为空表示全部数据源；时间为空表示全部分段。多实例下自动广播")
    @PostMapping("/evict")
    public R<Integer> evict(@RequestParam(required = false) String sourceCode,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return R.ok(segmentCache.evict(sourceCode, from, to));
    }

    @Operation(summary = "预热缓存", description = "提前加载指定数据源的时间窗口，避免定时任务集中触发时读库")
    @PostMapping("/warmup")
    public R<Map<String, Object>> warmup(@RequestParam String sourceCode,
                                         @RequestParam(required = false) List<String> seriesKeys,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        long begin = System.currentTimeMillis();
        long rows = dataService.read(sourceCode, seriesKeys, start, end, slice -> {
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("costMs", System.currentTimeMillis() - begin);
        return R.ok(result);
    }
}
