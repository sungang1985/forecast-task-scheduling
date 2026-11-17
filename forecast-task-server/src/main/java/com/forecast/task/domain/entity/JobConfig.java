package com.forecast.task.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务配置。
 */
@Data
@TableName("forecast_job_config")
public class JobConfig {

    public static final String POLICY_SKIP = "SKIP";
    public static final String POLICY_ALLOW = "ALLOW";
    public static final String MISFIRE_IGNORE = "IGNORE";
    public static final String MISFIRE_FIRE_ONCE = "FIRE_ONCE";

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String jobCode;
    private String jobName;
    private String taskType;
    private String mode;
    private String cronExpression;
    private String timeZone;
    /** JSON，结构见 {@link com.forecast.task.schedule.JobTaskTemplate} */
    private String taskParams;
    private Boolean enabled;
    private String concurrentPolicy;
    private String misfirePolicy;
    private Integer maxRetries;
    private Integer retryIntervalSeconds;
    private Integer timeoutSeconds;
    private String description;
    private LocalDateTime lastFireTime;
    private LocalDateTime nextFireTime;
    private String lastTaskId;
    private String lastStatus;
    @Version
    private Integer version;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 调度指纹：这些字段变化时需重新注册调度 */
    public String scheduleFingerprint() {
        return cronExpression + "|" + timeZone + "|" + enabled;
    }
}
