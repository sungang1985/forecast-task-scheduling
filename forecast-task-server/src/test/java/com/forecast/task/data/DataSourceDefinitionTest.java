package com.forecast.task.data;

import com.forecast.common.exception.BizException;
import com.forecast.task.domain.entity.DataSourceConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSourceDefinitionTest {

    private static DataSourceConfig wide() {
        DataSourceConfig cfg = new DataSourceConfig();
        cfg.setSourceCode("load_curve_96");
        cfg.setSourceName("负荷");
        cfg.setTableName("ods.ods_load_curve_96");
        cfg.setLayout("wide_points");
        cfg.setKeyColumn("area_code");
        cfg.setDateColumn("load_date");
        cfg.setPointsPerDay(96);
        cfg.setFieldMapping("{\"load\":\"p%03d\"}");
        cfg.setFixedFilters("{\"data_type\":\"ACTUAL\"}");
        return cfg;
    }

    @Test
    void parsesWideLayout() {
        DataSourceDefinition def = DataSourceDefinition.from(wide());
        assertThat(def.layout()).isEqualTo(DatasetLayout.WIDE_POINTS);
        assertThat(def.segmentUnit()).isEqualTo(SegmentUnit.MONTH);
        assertThat(def.minutesPerPoint()).isEqualTo(15);
        assertThat(def.fieldNames()).containsExactly("load");
        assertThat(DataSourceDefinition.formatPointColumn("p%03d", 96)).isEqualTo("p096");
        assertThat(def.cacheEnabled()).isTrue();
    }

    @Test
    void rejectsSqlInjectionInIdentifiers() {
        DataSourceConfig cfg = wide();
        cfg.setTableName("ods_load; DROP TABLE x");
        assertThatThrownBy(() -> DataSourceDefinition.from(cfg)).isInstanceOf(BizException.class);

        DataSourceConfig cfg2 = wide();
        cfg2.setFixedFilters("{\"1=1 OR a\":\"x\"}");
        assertThatThrownBy(() -> DataSourceDefinition.from(cfg2)).isInstanceOf(BizException.class);

        DataSourceConfig cfg3 = wide();
        cfg3.setFieldMapping("{\"load\":\"p%03d) UNION SELECT (1\"}");
        assertThatThrownBy(() -> DataSourceDefinition.from(cfg3)).isInstanceOf(BizException.class);
    }

    @Test
    void rejectsPointsNotDividingDay() {
        DataSourceConfig cfg = wide();
        cfg.setPointsPerDay(100);
        assertThatThrownBy(() -> DataSourceDefinition.from(cfg))
                .isInstanceOf(BizException.class).hasMessageContaining("1440");
    }

    @Test
    void longLayoutRequiresTimeColumn() {
        DataSourceConfig cfg = new DataSourceConfig();
        cfg.setSourceCode("weather");
        cfg.setSourceName("气象");
        cfg.setTableName("ods_weather");
        cfg.setLayout("LONG_TIME");
        cfg.setFieldMapping("{\"temperature\":\"temp\"}");
        assertThatThrownBy(() -> DataSourceDefinition.from(cfg)).hasMessageContaining("timeColumn");
        cfg.setTimeColumn("record_time");
        assertThat(DataSourceDefinition.from(cfg).hasKeyColumn()).isFalse();
    }
}
