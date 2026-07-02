package com.experiment.smartlightingexp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.experiment.smartlightingexp.entity.Telemetry;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TelemetryMapper extends BaseMapper<Telemetry> {
}
