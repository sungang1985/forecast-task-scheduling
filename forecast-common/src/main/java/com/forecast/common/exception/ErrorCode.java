package com.forecast.common.exception;

import lombok.Getter;

/**
 * 错误码定义。
 * <p>
 * 1xxxx 通用错误；2xxxx 任务相关；3xxxx 数据相关；4xxxx Python 引擎相关；5xxxx 调度相关。
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "success", 200),

    BAD_REQUEST(10400, "请求参数错误", 400),
    UNAUTHORIZED(10401, "未认证或认证失败", 401),
    FORBIDDEN(10403, "无权限访问", 403),
    NOT_FOUND(10404, "资源不存在", 404),
    CONFLICT(10409, "资源冲突", 409),
    TOO_MANY_REQUESTS(10429, "系统繁忙，请稍后重试", 429),
    INTERNAL_ERROR(10500, "系统内部错误", 500),
    REMOTE_CALL_FAILED(10502, "远程服务调用失败", 502),

    TASK_NOT_FOUND(20404, "预测任务不存在", 404),
    TASK_TYPE_UNSUPPORTED(20400, "不支持的预测任务类型", 400),
    TASK_STATE_INVALID(20409, "任务当前状态不允许该操作", 409),
    TASK_EXECUTE_FAILED(20500, "预测任务执行失败", 500),
    TASK_CANCELLED(20499, "预测任务已取消", 409),
    TASK_TIMEOUT(20504, "预测任务执行超时", 504),
    PIPELINE_NOT_FOUND(20501, "未找到预测流程定义", 500),

    DATA_SOURCE_NOT_FOUND(30404, "数据源配置不存在", 404),
    DATA_SOURCE_INVALID(30400, "数据源配置非法", 400),
    DATA_EMPTY(30410, "预训练数据为空", 422),
    DATA_READ_FAILED(30500, "读取预训练数据失败", 500),

    ENGINE_UNAVAILABLE(40503, "Python 预测引擎不可用", 503),
    ENGINE_CALL_FAILED(40500, "调用 Python 预测引擎失败", 502),
    ENGINE_TASK_FAILED(40510, "Python 引擎任务执行失败", 500),
    DATA_TRANSFER_FAILED(40520, "预训练数据分批传输失败", 502),
    MODEL_NOT_FOUND(40404, "模型不存在或不可用", 404),

    JOB_NOT_FOUND(50404, "定时任务配置不存在", 404),
    JOB_CRON_INVALID(50400, "Cron 表达式非法", 400),
    JOB_CODE_DUPLICATED(50409, "定时任务编码重复", 409);

    private final int code;
    private final String message;
    /** 对应的 HTTP 状态码 */
    private final int httpStatus;

    ErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
