package com.forecast.task.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.forecast.common.exception.BizException;
import com.forecast.common.exception.ErrorCode;
import com.forecast.common.model.PageResult;
import com.forecast.common.util.JsonUtils;
import com.forecast.task.domain.entity.ForecastModel;
import com.forecast.task.mapper.ForecastModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 模型注册表：训练产出的模型登记、版本切换与预测时的模型选择。
 */
@Service
public class ModelService {

    private final ForecastModelMapper mapper;

    public ModelService(ForecastModelMapper mapper) {
        this.mapper = mapper;
    }

    public static String seriesSignature(Collection<String> seriesKeys) {
        if (seriesKeys == null || seriesKeys.isEmpty()) {
            return ForecastModel.ALL_SERIES;
        }
        return String.join(",", new TreeSet<>(seriesKeys));
    }

    /**
     * 登记新模型并设为 ACTIVE，同一任务类型、同一预测对象集合与目标的旧模型置为 INACTIVE。
     */
    @Transactional(rollbackFor = Exception.class)
    public ForecastModel register(String taskType, String modelId, String algorithm, Collection<String> seriesKeys,
                                  List<String> targets, LocalDateTime trainStart, LocalDateTime trainEnd, Long trainRows,
                                  Map<String, Object> metrics, Map<String, Object> params, String sourceTaskId,
                                  String engineEndpoint) {
        String signature = seriesSignature(seriesKeys);
        String targetSignature = String.join(",", new TreeSet<>(targets));
        mapper.update(null, Wrappers.<ForecastModel>lambdaUpdate()
                .set(ForecastModel::getStatus, ForecastModel.STATUS_INACTIVE)
                .set(ForecastModel::getUpdatedAt, LocalDateTime.now())
                .eq(ForecastModel::getTaskType, taskType)
                .eq(ForecastModel::getSeriesKeys, signature)
                .eq(ForecastModel::getTargetFields, targetSignature)
                .eq(ForecastModel::getStatus, ForecastModel.STATUS_ACTIVE));
        ForecastModel model = new ForecastModel();
        model.setModelId(modelId);
        model.setTaskType(taskType);
        model.setAlgorithm(algorithm);
        model.setSeriesKeys(signature);
        model.setTargetFields(targetSignature);
        model.setTrainStart(trainStart);
        model.setTrainEnd(trainEnd);
        model.setTrainRows(trainRows);
        model.setMetricsJson(JsonUtils.toJson(metrics));
        model.setParamsJson(JsonUtils.toJson(params));
        model.setStatus(ForecastModel.STATUS_ACTIVE);
        model.setSourceTaskId(sourceTaskId);
        model.setEngineEndpoint(engineEndpoint);
        mapper.insert(model);
        return model;
    }

    /**
     * 选择可用模型：同任务类型的 ACTIVE 模型中，最新且覆盖请求的全部预测对象与目标的模型。
     */
    public ForecastModel findUsable(String taskType, Collection<String> seriesKeys, Collection<String> targets) {
        List<ForecastModel> candidates = mapper.selectList(Wrappers.<ForecastModel>lambdaQuery()
                .eq(ForecastModel::getTaskType, taskType)
                .eq(ForecastModel::getStatus, ForecastModel.STATUS_ACTIVE)
                .orderByDesc(ForecastModel::getCreatedAt)
                .last("LIMIT 50"));
        for (ForecastModel m : candidates) {
            if (covers(m.getSeriesKeys(), seriesKeys) && covers(m.getTargetFields(), targets)) {
                return m;
            }
        }
        throw new BizException(ErrorCode.MODEL_NOT_FOUND, String.format(
                "任务类型 %s 没有覆盖预测对象 %s、目标 %s 的可用模型，请先执行训练或一体化预测", taskType,
                seriesKeys == null || seriesKeys.isEmpty() ? "全部" : seriesKeys, targets));
    }

    public ForecastModel require(String modelId) {
        ForecastModel model = mapper.selectOne(Wrappers.<ForecastModel>lambdaQuery().eq(ForecastModel::getModelId, modelId));
        if (model == null) {
            throw new BizException(ErrorCode.MODEL_NOT_FOUND, "模型不存在: " + modelId);
        }
        return model;
    }

    /** 若平台未登记该模型（如直接在引擎侧训练的模型），返回 null 而不报错 */
    public ForecastModel find(String modelId) {
        return mapper.selectOne(Wrappers.<ForecastModel>lambdaQuery().eq(ForecastModel::getModelId, modelId));
    }

    @Transactional(rollbackFor = Exception.class)
    public ForecastModel activate(String modelId) {
        ForecastModel model = require(modelId);
        mapper.update(null, Wrappers.<ForecastModel>lambdaUpdate()
                .set(ForecastModel::getStatus, ForecastModel.STATUS_INACTIVE)
                .set(ForecastModel::getUpdatedAt, LocalDateTime.now())
                .eq(ForecastModel::getTaskType, model.getTaskType())
                .eq(ForecastModel::getSeriesKeys, model.getSeriesKeys())
                .eq(ForecastModel::getTargetFields, model.getTargetFields())
                .eq(ForecastModel::getStatus, ForecastModel.STATUS_ACTIVE));
        model.setStatus(ForecastModel.STATUS_ACTIVE);
        mapper.updateById(model);
        return model;
    }

    public PageResult<ForecastModel> page(String taskType, String status, long page, long size) {
        Page<ForecastModel> result = mapper.selectPage(Page.of(page, size), Wrappers.<ForecastModel>lambdaQuery()
                .eq(StringUtils.hasText(taskType), ForecastModel::getTaskType, taskType)
                .eq(StringUtils.hasText(status), ForecastModel::getStatus, status)
                .orderByDesc(ForecastModel::getCreatedAt));
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    private static boolean covers(String signature, Collection<String> required) {
        if (required == null || required.isEmpty()) {
            // 请求“全部预测对象”时，只有训练覆盖全部对象的模型可用
            return ForecastModel.ALL_SERIES.equals(signature);
        }
        if (ForecastModel.ALL_SERIES.equals(signature)) {
            return true;
        }
        Set<String> available = signature == null ? Set.of() : new HashSet<>(Arrays.asList(signature.split(",")));
        return available.containsAll(required);
    }
}
