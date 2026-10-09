package com.forecast.api.client;

import com.forecast.api.dto.ForecastPointVO;
import com.forecast.api.dto.ForecastTaskRequest;
import com.forecast.api.dto.TaskDetail;
import com.forecast.api.dto.TaskSubmitResult;
import com.forecast.common.exception.ErrorCode;
import com.forecast.common.model.PageResult;
import com.forecast.common.model.R;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * 熔断降级：服务不可用时返回统一错误，避免调用方线程长时间阻塞。
 * 需调用方开启 {@code spring.cloud.openfeign.circuitbreaker.enabled=true}。
 */
public class ForecastTaskClientFallbackFactory implements FallbackFactory<ForecastTaskClient> {

    @Override
    public ForecastTaskClient create(Throwable cause) {
        String message = "预测任务服务不可用: " + (cause == null ? "unknown" : cause.getMessage());
        return new ForecastTaskClient() {
            @Override
            public R<TaskSubmitResult> submit(String taskType, String mode, int waitSeconds, ForecastTaskRequest request) {
                return R.fail(ErrorCode.TOO_MANY_REQUESTS, message);
            }

            @Override
            public R<TaskDetail> getTask(String taskId) {
                return R.fail(ErrorCode.TOO_MANY_REQUESTS, message);
            }

            @Override
            public R<Boolean> cancel(String taskId) {
                return R.fail(ErrorCode.TOO_MANY_REQUESTS, message);
            }

            @Override
            public R<PageResult<ForecastPointVO>> getResults(String taskId, long page, long size) {
                return R.fail(ErrorCode.TOO_MANY_REQUESTS, message);
            }
        };
    }
}
