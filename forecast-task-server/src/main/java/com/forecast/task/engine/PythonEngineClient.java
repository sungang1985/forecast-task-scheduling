package com.forecast.task.engine;

import com.forecast.task.engine.dto.EngineDtos.CompleteDatasetRequest;
import com.forecast.task.engine.dto.EngineDtos.CompleteDatasetResponse;
import com.forecast.task.engine.dto.EngineDtos.CreateDatasetRequest;
import com.forecast.task.engine.dto.EngineDtos.EngineHealth;
import com.forecast.task.engine.dto.EngineDtos.EngineTaskRef;
import com.forecast.task.engine.dto.EngineDtos.EngineTaskStatus;
import com.forecast.task.engine.dto.EngineDtos.PredictRequest;
import com.forecast.task.engine.dto.EngineDtos.PredictionPage;
import com.forecast.task.engine.dto.EngineDtos.PreprocessRequest;
import com.forecast.task.engine.dto.EngineDtos.TrainRequest;

/**
 * Python 预测引擎客户端抽象。默认实现为 HTTP（{@link HttpPythonEngineClient}），
 * 如需 gRPC / 消息队列等方式，实现本接口并替换 Bean 即可，流程编排代码无需改动。
 */
public interface PythonEngineClient {

    EngineHealth health(EngineEndpoint endpoint);

    /** 创建数据集（分批上传的会话），返回 datasetId */
    String createDataset(EngineEndpoint endpoint, CreateDatasetRequest request);

    /**
     * 上传一个数据分片（幂等：同一 seq 重复上传会覆盖）。
     *
     * @param body     分片内容（JSON，可能已 gzip 压缩）
     * @param gzip     是否压缩
     * @param rows     分片行数，用于服务端校验
     * @param checksum 内容 CRC32（十六进制），用于服务端校验完整性
     */
    void uploadChunk(EngineEndpoint endpoint, String datasetId, String part, int seq, byte[] body,
                     boolean gzip, int rows, String checksum);

    /** 结束上传：服务端校验分片完整性并合并 */
    CompleteDatasetResponse completeDataset(EngineEndpoint endpoint, String datasetId, CompleteDatasetRequest request);

    void deleteDataset(EngineEndpoint endpoint, String datasetId);

    EngineTaskRef submitPreprocess(EngineEndpoint endpoint, PreprocessRequest request);

    EngineTaskRef submitTrain(EngineEndpoint endpoint, TrainRequest request);

    EngineTaskRef submitPredict(EngineEndpoint endpoint, PredictRequest request);

    EngineTaskStatus getTask(EngineEndpoint endpoint, String engineTaskId);

    void cancelTask(EngineEndpoint endpoint, String engineTaskId);

    /** 分页拉取预测结果 */
    PredictionPage getPredictions(EngineEndpoint endpoint, String predictionId, int offset, int limit);
}
