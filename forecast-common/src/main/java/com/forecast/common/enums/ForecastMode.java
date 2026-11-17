package com.forecast.common.enums;

import java.util.Locale;

/**
 * 预测任务执行模式，对应不同的流程编排。
 */
public enum ForecastMode {
    /** 数据预处理 */
    PREPROCESS,
    /** 模型训练（未指定预处理数据集时会自动执行取数与预处理） */
    TRAIN,
    /** 结果预测 */
    PREDICT,
    /** 一体化预测：取数 -> 预处理 -> 训练 -> 预测 -> 结果入库 */
    FULL;

    public static ForecastMode parse(String value) {
        return ForecastMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
