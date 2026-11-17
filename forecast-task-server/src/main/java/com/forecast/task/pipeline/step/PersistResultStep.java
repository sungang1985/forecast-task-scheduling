package com.forecast.task.pipeline.step;

import com.forecast.task.config.ForecastProperties;
import com.forecast.task.engine.EngineEndpoint;
import com.forecast.task.engine.PythonEngineClient;
import com.forecast.task.engine.dto.EngineDtos.PredictionPage;
import com.forecast.task.engine.dto.EngineDtos.PredictionRow;
import com.forecast.task.pipeline.ContextKeys;
import com.forecast.task.pipeline.ForecastStep;
import com.forecast.task.pipeline.PipelineContext;
import com.forecast.task.pipeline.StepResult;
import com.forecast.task.service.ResultSink;
import com.forecast.task.service.ResultSink.ResultContext;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结果入库：分页从 Python 引擎拉取预测结果（大结果集同样分批传输），依次写入所有 {@link ResultSink}。
 */
@Component
public class PersistResultStep implements ForecastStep {

    public static final String NAME = "PERSIST_RESULT";

    private final PythonEngineClient client;
    private final EngineStepSupport support;
    private final List<ResultSink> sinks;
    private final ForecastProperties properties;

    public PersistResultStep(PythonEngineClient client, EngineStepSupport support, List<ResultSink> sinks,
                             ForecastProperties properties) {
        this.client = client;
        this.support = support;
        this.sinks = sinks;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "预测结果分页拉取并入库";
    }

    @Override
    public StepResult execute(PipelineContext ctx) {
        String predictionId = ctx.get(ContextKeys.PREDICTION_ID);
        EngineEndpoint endpoint = support.endpoint(ctx);
        ResultContext resultContext = new ResultContext(ctx.getTaskId(), ctx.getTaskType(), ctx.getJobId(),
                ctx.get(ContextKeys.MODEL_ID));
        sinks.forEach(sink -> sink.prepare(resultContext));

        int pageSize = properties.getEngine().getResultPageSize();
        int offset = 0;
        long total;
        long written = 0;
        Set<String> series = new HashSet<>();
        Set<String> targets = new HashSet<>();
        do {
            ctx.checkActive();
            PredictionPage page = client.getPredictions(endpoint, predictionId, offset, pageSize);
            total = page.total();
            List<PredictionRow> rows = page.rows() == null ? List.of() : page.rows();
            if (rows.isEmpty()) {
                break;
            }
            for (ResultSink sink : sinks) {
                sink.write(resultContext, rows);
            }
            rows.forEach(r -> {
                series.add(r.seriesKey());
                targets.add(r.target());
            });
            written += rows.size();
            offset += rows.size();
            ctx.reportProgress(total == 0 ? 100 : (int) (written * 100 / total), "已入库 " + written + "/" + total);
        } while (offset < total);

        ctx.summary("resultRows", written);
        ctx.summary("resultSeries", series.size());
        ctx.summary("resultTargets", targets);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("predictionId", predictionId);
        output.put("rows", written);
        output.put("series", series.size());
        output.put("sinks", sinks.stream().map(ResultSink::name).toList());
        return StepResult.of("已入库 " + written + " 条预测结果", output);
    }
}
