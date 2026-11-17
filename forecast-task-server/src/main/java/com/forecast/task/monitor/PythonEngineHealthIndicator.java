package com.forecast.task.monitor;

import com.forecast.task.engine.EngineEndpoint;
import com.forecast.task.engine.EngineEndpointResolver;
import com.forecast.task.engine.PythonEngineClient;
import com.forecast.task.engine.dto.EngineDtos.EngineHealth;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Python 引擎健康检查，纳入 /actuator/health（组件名 pythonEngine）。
 */
@Component("pythonEngine")
public class PythonEngineHealthIndicator implements HealthIndicator {

    private final EngineEndpointResolver resolver;
    private final PythonEngineClient client;
    private final CircuitBreaker circuitBreaker;

    public PythonEngineHealthIndicator(EngineEndpointResolver resolver, PythonEngineClient client,
                                       CircuitBreaker engineCircuitBreaker) {
        this.resolver = resolver;
        this.client = client;
        this.circuitBreaker = engineCircuitBreaker;
    }

    @Override
    public Health health() {
        try {
            EngineEndpoint endpoint = resolver.resolve();
            EngineHealth health = client.health(endpoint);
            Health.Builder builder = "UP".equalsIgnoreCase(health.status()) ? Health.up() : Health.down();
            return builder.withDetail("endpoint", endpoint.baseUrl())
                    .withDetail("version", health.version())
                    .withDetail("workers", health.workers())
                    .withDetail("runningTasks", health.runningTasks())
                    .withDetail("queuedTasks", health.queuedTasks())
                    .withDetail("circuitBreaker", circuitBreaker.getState().name())
                    .build();
        } catch (RuntimeException e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("circuitBreaker", circuitBreaker.getState().name())
                    .build();
        }
    }
}
