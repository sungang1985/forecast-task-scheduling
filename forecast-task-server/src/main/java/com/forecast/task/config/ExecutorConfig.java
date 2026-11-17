package com.forecast.task.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池配置。各线程池相互隔离，避免数据传输、回调等慢操作挤占预测任务执行资源。
 * Spring Boot Actuator 会自动为这些线程池注册 executor.* 指标。
 */
@Configuration
public class ExecutorConfig {

    public static final String TASK_EXECUTOR = "forecastTaskExecutor";
    public static final String TRANSFER_EXECUTOR = "dataTransferExecutor";

    /**
     * 预测任务执行线程池：有界队列 + AbortPolicy，队列满时拒绝并向调用方返回“系统繁忙”，实现过载保护。
     */
    @Bean(name = TASK_EXECUTOR)
    public ThreadPoolTaskExecutor forecastTaskExecutor(ForecastProperties properties) {
        ForecastProperties.Executor cfg = properties.getExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("forecast-task-");
        executor.setCorePoolSize(cfg.getCorePoolSize());
        executor.setMaxPoolSize(cfg.getMaxPoolSize());
        executor.setQueueCapacity(cfg.getQueueCapacity());
        executor.setKeepAliveSeconds(cfg.getKeepAliveSeconds());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(cfg.getAwaitTerminationSeconds());
        executor.setTaskDecorator(mdcTaskDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * 数据分片上传线程池：调用方线程执行兜底（CallerRunsPolicy），形成天然背压。
     */
    @Bean(name = TRANSFER_EXECUTOR)
    public ThreadPoolTaskExecutor dataTransferExecutor(ForecastProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("data-transfer-");
        int size = properties.getExecutor().getTransferPoolSize();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(size * 4);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(mdcTaskDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * 将提交线程的 MDC（traceId 等）传递到异步线程，保证日志可追踪。
     */
    private static TaskDecorator mdcTaskDecorator() {
        return runnable -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (context != null) {
                    MDC.setContextMap(context);
                } else {
                    MDC.clear();
                }
                try {
                    runnable.run();
                } finally {
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        };
    }
}
