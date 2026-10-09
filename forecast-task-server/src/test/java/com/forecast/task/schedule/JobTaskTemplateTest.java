package com.forecast.task.schedule;

import com.forecast.api.dto.ForecastTaskRequest;
import com.forecast.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobTaskTemplateTest {

    @Test
    void dayAheadWindowRelativeToFireTime() {
        JobTaskTemplate template = new JobTaskTemplate();
        template.setSeriesKeys(List.of("AREA_01"));
        template.setHistoryDays(60);
        template.setHistoryEndOffsetDays(0);
        template.setForecastStartOffsetDays(1);
        template.setHorizonHours(24);

        ForecastTaskRequest req = template.toRequest(LocalDateTime.of(2026, 10, 9, 6, 0), 3600, "JOB-1");

        assertThat(req.getHistoryEnd()).isEqualTo(LocalDateTime.of(2026, 10, 9, 0, 0));
        assertThat(req.getHistoryStart()).isEqualTo(LocalDateTime.of(2026, 8, 10, 0, 0));
        assertThat(req.getForecastStart()).isEqualTo(LocalDateTime.of(2026, 10, 10, 0, 0));
        assertThat(req.getForecastEnd()).isEqualTo(LocalDateTime.of(2026, 10, 11, 0, 0));
        assertThat(req.getRequestId()).isEqualTo("JOB-1");
        assertThat(req.getTimeoutSeconds()).isEqualTo(3600);
    }

    @Test
    void historyEndNeverExceedsForecastStart() {
        JobTaskTemplate template = new JobTaskTemplate();
        template.setHistoryEndOffsetDays(1);
        template.setForecastStartOffsetDays(0);
        ForecastTaskRequest req = template.toRequest(LocalDateTime.of(2026, 10, 9, 0, 15), null, null);
        assertThat(req.getHistoryEnd()).isEqualTo(req.getForecastStart());
    }

    @Test
    void cronPreviewAndValidation() {
        List<LocalDateTime> times = JobConfigService.preview("0 0 6 * * *", "Asia/Shanghai", 3);
        assertThat(times).hasSize(3);
        assertThat(times.get(1)).isEqualTo(times.get(0).plusDays(1));
        assertThatThrownBy(() -> JobConfigService.parseCron("0 0 6 * *")).isInstanceOf(BizException.class);
    }
}
