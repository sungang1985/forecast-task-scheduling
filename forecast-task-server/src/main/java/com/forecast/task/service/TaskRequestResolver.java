package com.forecast.task.service;

import com.forecast.api.dto.ForecastTaskRequest;
import com.forecast.common.enums.ForecastMode;
import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import com.forecast.common.util.JsonUtils;
import com.forecast.task.config.ForecastProperties;
import com.forecast.task.config.ForecastProperties.TaskTypeProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * 补全并校验请求参数：时间窗口默认值、目标字段、算法、参数合并、超时时间。
 * 解析后的完整请求会写入任务记录，保证任务可复现、可追溯。
 */
@Component
public class TaskRequestResolver {

    /** 单次预测时长上限 */
    private static final Duration MAX_HORIZON = Duration.ofDays(31);
    /** 历史窗口上限 */
    private static final Duration MAX_HISTORY = Duration.ofDays(3 * 366);

    private final ForecastProperties properties;

    public TaskRequestResolver(ForecastProperties properties) {
        this.properties = properties;
    }

    public ForecastTaskRequest resolve(TaskTypeProperties def, ForecastMode mode, ForecastTaskRequest source) {
        // 深拷贝，避免修改调用方对象
        ForecastTaskRequest req = source == null ? new ForecastTaskRequest()
                : JsonUtils.mapper().convertValue(source, ForecastTaskRequest.class);
        LocalDateTime today = LocalDate.now().atStartOfDay();
        boolean needsForecastWindow = mode == ForecastMode.PREDICT || mode == ForecastMode.FULL;

        if (needsForecastWindow) {
            if (req.getForecastStart() == null) {
                req.setForecastStart(today.plusDays(1));
            }
            if (req.getForecastEnd() == null) {
                req.setForecastEnd(req.getForecastStart().plusHours(def.getDefaultHorizonHours()));
            }
            if (!req.getForecastStart().isBefore(req.getForecastEnd())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "forecastStart 必须早于 forecastEnd");
            }
            if (Duration.between(req.getForecastStart(), req.getForecastEnd()).compareTo(MAX_HORIZON) > 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "预测时长不能超过 " + MAX_HORIZON.toDays() + " 天");
            }
        }

        if (req.getHistoryEnd() == null) {
            LocalDateTime end = today;
            if (needsForecastWindow && req.getForecastStart().isBefore(end)) {
                end = req.getForecastStart();
            }
            req.setHistoryEnd(end);
        }
        if (req.getHistoryStart() == null) {
            int days = mode == ForecastMode.PREDICT ? def.getPredictLookbackDays() : def.getDefaultHistoryDays();
            req.setHistoryStart(req.getHistoryEnd().minusDays(days));
        }
        if (!req.getHistoryStart().isBefore(req.getHistoryEnd())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "historyStart 必须早于 historyEnd");
        }
        if (Duration.between(req.getHistoryStart(), req.getHistoryEnd()).compareTo(MAX_HISTORY) > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "历史数据窗口不能超过 3 年");
        }
        if (needsForecastWindow && req.getHistoryEnd().isAfter(req.getForecastStart())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "historyEnd 不能晚于 forecastStart（避免数据泄漏）");
        }

        req.setSeriesKeys(req.getSeriesKeys() == null ? new ArrayList<>()
                : new ArrayList<>(new LinkedHashSet<>(req.getSeriesKeys().stream().filter(StringUtils::hasText).toList())));
        if (req.getTargetFields() == null || req.getTargetFields().isEmpty()) {
            req.setTargetFields(new ArrayList<>(def.getTargetFields()));
        }
        if (req.getTargetFields().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未配置预测目标字段");
        }
        if (!StringUtils.hasText(req.getAlgorithm())) {
            req.setAlgorithm(def.getDefaultAlgorithm());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        def.getParams().forEach((k, v) -> params.put(k, normalizeBound(v)));
        if (req.getParams() != null) {
            params.putAll(req.getParams());
        }
        req.setParams(params);
        if (req.getTimeoutSeconds() == null) {
            Duration timeout = def.getTimeout() != null ? def.getTimeout() : properties.getTask().getDefaultTimeout();
            req.setTimeoutSeconds((int) timeout.toSeconds());
        }
        return req;
    }

    /**
     * Spring Boot 把 YAML 列表绑定到 {@code Map<String, Object>} 时会得到以 "0","1"... 为键的 Map，
     * 这里递归还原为 List，保证透传给 Python 的参数结构与配置一致（如 lags: [96, 672]）。
     */
    @SuppressWarnings("unchecked")
    static Object normalizeBound(Object value) {
        if (value instanceof Map<?, ?> map) {
            boolean indexed = !map.isEmpty();
            for (int i = 0; i < map.size() && indexed; i++) {
                indexed = map.containsKey(String.valueOf(i));
            }
            if (indexed) {
                java.util.List<Object> list = new ArrayList<>(map.size());
                for (int i = 0; i < map.size(); i++) {
                    list.add(normalizeBound(map.get(String.valueOf(i))));
                }
                return list;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> copy.put(k, normalizeBound(v)));
            return copy;
        }
        if (value instanceof java.util.Collection<?> collection) {
            return collection.stream().map(TaskRequestResolver::normalizeBound).toList();
        }
        return value;
    }
}
