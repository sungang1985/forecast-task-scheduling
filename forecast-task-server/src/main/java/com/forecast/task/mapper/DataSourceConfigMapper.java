package com.forecast.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.forecast.task.domain.entity.DataSourceConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DataSourceConfigMapper extends BaseMapper<DataSourceConfig> {
}
