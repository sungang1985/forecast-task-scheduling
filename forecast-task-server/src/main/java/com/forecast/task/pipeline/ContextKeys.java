package com.forecast.task.pipeline;

/**
 * 步骤间传递产物的上下文键。
 */
public final class ContextKeys {

    private ContextKeys() {
    }

    /** Python 端原始数据集 */
    public static final String DATASET_ID = "datasetId";
    /** Python 端预处理后数据集 */
    public static final String PROCESSED_DATASET_ID = "processedDatasetId";
    public static final String MODEL_ID = "modelId";
    public static final String PREDICTION_ID = "predictionId";
    /** 上传的数据行数（按分区） */
    public static final String DATA_ROWS = "dataRows";
}
