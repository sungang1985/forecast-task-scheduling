package com.forecast.task.pipeline;

import com.forecast.api.dto.ForecastTaskRequest;
import com.forecast.common.enums.ForecastMode;
import com.forecast.common.enums.StepStatus;
import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import com.forecast.task.config.ForecastProperties;
import com.forecast.task.engine.EngineCallException;
import com.forecast.task.service.TaskRecordService;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineExecutorTest {

    private TaskRecordService records;
    private PipelineExecutor executor;
    private final List<String> executed = new ArrayList<>();

    @BeforeEach
    void setUp() {
        records = mock(TaskRecordService.class);
        when(records.stepStarted(anyString(), anyInt(), anyString(), anyInt())).thenReturn(1L);
        ForecastProperties props = new ForecastProperties();
        props.getTask().setStepMaxAttempts(3);
        props.getTask().setStepRetryBackoff(Duration.ofMillis(1));
        executor = new PipelineExecutor(records, mock(ApplicationEventPublisher.class), ObservationRegistry.NOOP, props);
    }

    private PipelineContext ctx(Instant deadline) {
        return new PipelineContext("T1", "LOAD", ForecastMode.FULL, new ForecastProperties.TaskTypeProperties(),
                new ForecastTaskRequest(), null, deadline);
    }

    private ForecastStep step(String name, Predicate<PipelineContext> skip, Function<PipelineContext, StepResult> body) {
        return new ForecastStep() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean shouldSkip(PipelineContext ctx) {
                return skip.test(ctx);
            }

            @Override
            public StepResult execute(PipelineContext ctx) {
                executed.add(name);
                return body.apply(ctx);
            }
        };
    }

    @Test
    void runsStepsInOrderSkipsAndRunsCleanup() {
        PipelineContext ctx = ctx(null);
        AtomicInteger cleaned = new AtomicInteger();
        executor.execute(ctx, List.of(
                step("A", c -> false, c -> {
                    c.put("x", 1);
                    c.addCleanup(cleaned::incrementAndGet);
                    return StepResult.of("ok", Map.of());
                }),
                step("B", c -> c.has("x"), c -> StepResult.of("never", Map.of())),
                step("C", c -> false, c -> StepResult.of("ok", Map.of()))));

        assertThat(executed).containsExactly("A", "C");
        assertThat(cleaned.get()).isEqualTo(1);
        verify(records).stepSkipped(eq("T1"), eq(2), eq("B"), anyString());
        verify(records, times(2)).stepFinished(anyLong(), eq(StepStatus.SUCCESS), any(), any(), any(), anyLong());
    }

    @Test
    void retriesTransientEngineFailureThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        executor.execute(ctx(null), List.of(step("FLAKY", c -> false, c -> {
            if (calls.incrementAndGet() < 3) {
                throw new EngineCallException(ErrorCode.ENGINE_UNAVAILABLE, "boom", true, 503, null);
            }
            return StepResult.of("ok", Map.of());
        })));
        assertThat(calls.get()).isEqualTo(3);
        verify(records, times(2)).stepFinished(anyLong(), eq(StepStatus.FAILED), any(), any(), any(), anyLong());
    }

    @Test
    void businessErrorIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute(ctx(null), List.of(step("BAD", c -> false, c -> {
            calls.incrementAndGet();
            throw new BizException(ErrorCode.DATA_EMPTY, "no data");
        })))).isInstanceOf(BizException.class).hasMessage("no data");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void cancelledContextStopsBeforeNextStepAndStillCleansUp() {
        PipelineContext ctx = ctx(null);
        AtomicInteger cleaned = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute(ctx, List.of(
                step("A", c -> false, c -> {
                    c.addCleanup(cleaned::incrementAndGet);
                    c.requestCancel();
                    return StepResult.of("ok", Map.of());
                }),
                step("B", c -> false, c -> StepResult.of("ok", Map.of())))))
                .isInstanceOf(TaskCancelledException.class);
        assertThat(executed).containsExactly("A");
        assertThat(cleaned.get()).isEqualTo(1);
    }

    @Test
    void expiredDeadlineRaisesTimeout() {
        assertThatThrownBy(() -> executor.execute(ctx(Instant.now().minusSeconds(1)),
                List.of(step("A", c -> false, c -> StepResult.of("ok", Map.of())))))
                .isInstanceOf(TaskTimeoutException.class);
        assertThat(executed).isEmpty();
    }
}
