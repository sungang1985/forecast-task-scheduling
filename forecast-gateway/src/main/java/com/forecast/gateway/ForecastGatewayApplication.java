package com.forecast.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * API 网关启动类。
 */
@SpringBootApplication
@EnableDiscoveryClient
@ConfigurationPropertiesScan
public class ForecastGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForecastGatewayApplication.class, args);
    }
}
