package com.forecast.task;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 预测任务服务启动类。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ForecastTaskApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForecastTaskApplication.class, args);
    }
}
