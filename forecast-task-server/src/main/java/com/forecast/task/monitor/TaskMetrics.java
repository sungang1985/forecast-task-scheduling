package com.forecast.task.monitor;

import com.forecast.task.service.RunningTaskRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 任务业务指标（Prometheus 抓取 /actuator/prometheus）：
 * <ul>
 *     <li>forecast_task_submitted_total{type,mode} 提交数</li>
 *     <li>forecast_task_finished_total{type,mode,status} 结束数（成功率、失败率告警）</li>
 *     <li>forecast_task_running / forecast_task_queued 本节点运行与排队任务数</li>
 *     <li>forecast_task_seconds / forecast_task_step_seconds 由 Observation 自动产生的耗时分布</li>
 * </ul>
 */
@Component
public class TaskMetrics {

    private final MeterRegistry registry;

    public TaskMetrics(MeterRegistry registry, RunningTaskRegistry runningRegistry) {
        this.registry = registry;
        Gauge.builder("forecast.task.running", runningRegistry, RunningTaskRegistry::runningCount)
                .description("本节点运行中的预测任务数").register(registry);
        Gauge.builder("forecast.task.queued", runningRegistry, RunningTaskRegistry::queuedCount)
                .description("本节点排队中的预测任务数").register(registry);
    }

    @EventListener
    public void onTaskEvent(TaskEvent event) {
        switch (event.type()) {
            case SUBMITTED -> Counter.builder("forecast.task.submitted")
                    .tag("type", event.taskType()).tag("mode", event.mode())
                    .register(registry).increment();
            case FINISHED -> Counter.builder("forecast.task.finished")
                    .tag("type", event.taskType()).tag("mode", event.mode()).tag("status", event.status())
                    .register(registry).increment();
            default -> {
            }
        }
    }
}
