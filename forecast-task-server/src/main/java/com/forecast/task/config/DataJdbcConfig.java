package com.forecast.task.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.Data;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

/**
 * 预训练数据读取专用 JdbcTemplate。
 * <p>
 * 业务数据可能与平台库不在同一个数据库，配置 forecast.business-datasource.url 时使用独立连接池
 * （与平台库连接池隔离，大查询不影响任务状态读写）；否则复用平台主数据源。
 */
@Configuration
public class DataJdbcConfig {

    @Bean
    @ConfigurationProperties(prefix = "forecast.business-datasource")
    public BusinessDataSourceProperties businessDataSourceProperties() {
        return new BusinessDataSourceProperties();
    }

    @Bean
    public BusinessDataSourceHolder businessDataSourceHolder(BusinessDataSourceProperties props, DataSource primary) {
        if (!StringUtils.hasText(props.getUrl())) {
            return new BusinessDataSourceHolder(primary, null);
        }
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("business-data-pool");
        ds.setJdbcUrl(props.getUrl());
        ds.setUsername(props.getUsername());
        ds.setPassword(props.getPassword());
        if (StringUtils.hasText(props.getDriverClassName())) {
            ds.setDriverClassName(props.getDriverClassName());
        }
        ds.setMaximumPoolSize(props.getMaximumPoolSize());
        ds.setReadOnly(true);
        return new BusinessDataSourceHolder(ds, ds);
    }

    @Bean(name = "dataJdbcTemplate")
    public NamedParameterJdbcTemplate dataJdbcTemplate(BusinessDataSourceHolder holder, ForecastProperties properties) {
        JdbcTemplate template = new JdbcTemplate(holder.dataSource());
        template.setFetchSize(properties.getCache().getFetchSize());
        return new NamedParameterJdbcTemplate(template);
    }

    @Data
    public static class BusinessDataSourceProperties {
        private String url;
        private String username;
        private String password;
        private String driverClassName;
        private int maximumPoolSize = 10;
    }

    /**
     * 持有业务数据源，独立连接池在容器关闭时释放。
     */
    public record BusinessDataSourceHolder(DataSource dataSource, HikariDataSource owned) implements DisposableBean {
        @Override
        public void destroy() {
            if (owned != null) {
                owned.close();
            }
        }
    }
}
