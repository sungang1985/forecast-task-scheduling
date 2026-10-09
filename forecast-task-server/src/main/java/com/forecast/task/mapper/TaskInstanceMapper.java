package com.forecast.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.forecast.task.domain.entity.TaskInstance;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TaskInstanceMapper extends BaseMapper<TaskInstance> {
}
