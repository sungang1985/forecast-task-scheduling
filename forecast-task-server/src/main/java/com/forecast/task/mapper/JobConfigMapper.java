package com.forecast.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.forecast.task.domain.entity.JobConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface JobConfigMapper extends BaseMapper<JobConfig> {
}
