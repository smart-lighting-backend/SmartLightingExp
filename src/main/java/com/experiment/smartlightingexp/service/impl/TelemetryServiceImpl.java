package com.experiment.smartlightingexp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.experiment.smartlightingexp.entity.Telemetry;
import com.experiment.smartlightingexp.mapper.TelemetryMapper;
import com.experiment.smartlightingexp.service.TelemetryService;
import org.springframework.stereotype.Service;

/**
 * 遥测数据 Service 实现 — 遥测数据写入和查询的业务逻辑。
 */
@Service
public class TelemetryServiceImpl extends ServiceImpl<TelemetryMapper, Telemetry> implements TelemetryService {
}
