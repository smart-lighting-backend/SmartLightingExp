package com.experiment.smartlightingexp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.experiment.smartlightingexp.entity.DecisionLog;

/**
 * 策略决策日志 Service — 记录策略引擎每次决策的输入、命中和执行结果。
 */
public interface DecisionLogService extends IService<DecisionLog> {
}
