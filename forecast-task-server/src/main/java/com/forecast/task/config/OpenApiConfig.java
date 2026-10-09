package com.forecast.task.config;

import com.forecast.common.constant.TraceHeaders;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档：/swagger-ui.html
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI forecastOpenApi() {
        return new OpenAPI()
                .info(new Info().title("预测任务服务 API").version("1.0.0")
                        .description("量价/负荷预测：数据预处理、模型训练、结果预测、一体化预测；定时任务；数据源与缓存；监控追踪"))
                .components(new Components().addSecuritySchemes("apiKey", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name(TraceHeaders.API_KEY)))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"));
    }
}
