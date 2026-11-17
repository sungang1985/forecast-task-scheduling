package com.forecast.task;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 预测任务服务启动类。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
@ConfigurationPropertiesScan
@MapperScan("com.forecast.task.mapper")
public class ForecastTaskApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForecastTaskApplication.class, args);
    }
}
