package com.forecast.task.schedule;

import com.forecast.api.dto.ForecastTaskRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务参数模板（forecast_job_config.task_params）。时间窗口相对“触发日期”计算，
 * 例如每天 06:00 触发的日前预测：historyEndOffsetDays=0、forecastStartOffsetDays=1、horizonHours=24，
 * 即用截至今日 0 点的历史数据预测明日 0~24 点。
 */
@Data
@Schema(description = "定时任务参数模板")
public class JobTaskTemplate {

    private List<String> seriesKeys = new ArrayList<>();
    private List<String> targetFields = new ArrayList<>();
    /** 历史窗口天数，为空取任务类型默认值 */
    private Integer historyDays;
    /** historyEnd = 触发日期 0 点 + N 天 */
    private int historyEndOffsetDays = 0;
    /** forecastStart = 触发日期 0 点 + N 天 */
    private int forecastStartOffsetDays = 1;
    /** 预测时长（小时），为空取任务类型默认值 */
    private Integer horizonHours;
    private String algorithm;
    private String modelId;
    private Map<String, Object> params = new LinkedHashMap<>();
    private String callbackUrl;

    /**
     * 按触发时间生成本次任务请求。
     */
    public ForecastTaskRequest toRequest(LocalDateTime fireTime, Integer timeoutSeconds, String requestId) {
        LocalDateTime day = LocalDate.from(fireTime).atStartOfDay();
        ForecastTaskRequest req = new ForecastTaskRequest();
        req.setRequestId(requestId);
        req.setSeriesKeys(new ArrayList<>(seriesKeys));
        req.setTargetFields(new ArrayList<>(targetFields));
        LocalDateTime forecastStart = day.plusDays(forecastStartOffsetDays);
        req.setForecastStart(forecastStart);
        if (horizonHours != null) {
            req.setForecastEnd(forecastStart.plusHours(horizonHours));
        }
        LocalDateTime historyEnd = day.plusDays(historyEndOffsetDays);
        if (historyEnd.isAfter(forecastStart)) {
            historyEnd = forecastStart;
        }
        req.setHistoryEnd(historyEnd);
        if (historyDays != null) {
            req.setHistoryStart(historyEnd.minusDays(historyDays));
        }
        req.setAlgorithm(algorithm);
        req.setModelId(modelId);
        req.setParams(new LinkedHashMap<>(params));
        req.setCallbackUrl(callbackUrl);
        req.setTimeoutSeconds(timeoutSeconds);
        return req;
    }
}
