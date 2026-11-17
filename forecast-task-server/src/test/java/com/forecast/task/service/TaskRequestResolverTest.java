package com.forecast.task.service;

import com.forecast.api.dto.ForecastTaskRequest;
import com.forecast.common.enums.ForecastMode;
import com.forecast.common.exception.BizException;
import com.forecast.task.config.ForecastProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskRequestResolverTest {

    private final TaskRequestResolver resolver = new TaskRequestResolver(new ForecastProperties());

    private static ForecastProperties.TaskTypeProperties loadType() {
        ForecastProperties.TaskTypeProperties def = new ForecastProperties.TaskTypeProperties();
        def.setTargetFields(List.of("load"));
        def.setDefaultHistoryDays(90);
        def.setPredictLookbackDays(14);
        def.setDefaultHorizonHours(24);
        // 模拟 Spring Boot 将 YAML 列表绑定进 Map<String, Object> 后的形态
        Map<String, Object> lags = new LinkedHashMap<>();
        lags.put("0", 96);
        lags.put("1", 672);
        def.setParams(new LinkedHashMap<>(Map.of("lags", lags, "outlier_method", "iqr")));
        return def;
    }

    @Test
    void fillsDefaultWindowsAndRestoresBoundLists() {
        ForecastTaskRequest req = resolver.resolve(loadType(), ForecastMode.FULL, new ForecastTaskRequest());
        LocalDateTime today = LocalDate.now().atStartOfDay();
        assertThat(req.getForecastStart()).isEqualTo(today.plusDays(1));
        assertThat(req.getForecastEnd()).isEqualTo(today.plusDays(2));
        assertThat(req.getHistoryEnd()).isEqualTo(today);
        assertThat(req.getHistoryStart()).isEqualTo(today.minusDays(90));
        assertThat(req.getTargetFields()).containsExactly("load");
        assertThat(req.getParams().get("lags")).isEqualTo(List.of(96, 672));
        assertThat(req.getTimeoutSeconds()).isEqualTo(7200);
    }

    @Test
    void predictUsesLookbackAndRequestParamsOverrideDefaults() {
        ForecastTaskRequest in = new ForecastTaskRequest();
        in.setParams(Map.of("lags", List.of(48)));
        ForecastTaskRequest req = resolver.resolve(loadType(), ForecastMode.PREDICT, in);
        assertThat(req.getHistoryStart()).isEqualTo(req.getHistoryEnd().minusDays(14));
        assertThat(req.getParams().get("lags")).isEqualTo(List.of(48));
    }

    @Test
    void rejectsLeakingOrInvertedWindows() {
        ForecastTaskRequest leak = new ForecastTaskRequest();
        leak.setForecastStart(LocalDateTime.of(2026, 1, 2, 0, 0));
        leak.setHistoryEnd(LocalDateTime.of(2026, 1, 3, 0, 0));
        assertThatThrownBy(() -> resolver.resolve(loadType(), ForecastMode.FULL, leak)).isInstanceOf(BizException.class);

        ForecastTaskRequest inverted = new ForecastTaskRequest();
        inverted.setForecastStart(LocalDateTime.of(2026, 1, 2, 0, 0));
        inverted.setForecastEnd(LocalDateTime.of(2026, 1, 1, 0, 0));
        assertThatThrownBy(() -> resolver.resolve(loadType(), ForecastMode.PREDICT, inverted)).isInstanceOf(BizException.class);
    }
}
