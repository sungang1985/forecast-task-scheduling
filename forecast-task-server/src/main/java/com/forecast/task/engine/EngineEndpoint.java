package com.forecast.task.engine;

/**
 * Python 引擎实例地址。一次预测任务的所有调用固定在同一实例上（实例亲和），
 * 保证分批上传的数据集、预处理结果与模型位于同一实例的存储中。
 */
public record EngineEndpoint(String instanceId, String baseUrl) {

    public String url(String path) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) + path : baseUrl + path;
    }

    @Override
    public String toString() {
        return baseUrl;
    }
}
