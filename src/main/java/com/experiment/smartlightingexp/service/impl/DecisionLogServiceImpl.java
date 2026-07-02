package com.experiment.smartlightingexp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.experiment.smartlightingexp.entity.DecisionLog;
import com.experiment.smartlightingexp.mapper.DecisionLogMapper;
import com.experiment.smartlightingexp.service.DecisionLogService;
import org.springframework.stereotype.Service;

/**
 * 策略决策日志 Service 实现。
 */
@Service
public class DecisionLogServiceImpl extends ServiceImpl<DecisionLogMapper, DecisionLog> implements DecisionLogService {
}
