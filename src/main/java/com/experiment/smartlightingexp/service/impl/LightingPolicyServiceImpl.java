package com.experiment.smartlightingexp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.experiment.smartlightingexp.entity.LightingPolicy;
import com.experiment.smartlightingexp.mapper.LightingPolicyMapper;
import com.experiment.smartlightingexp.service.LightingPolicyService;
import org.springframework.stereotype.Service;

/**
 * 照明策略 Service 实现 — 策略配置、匹配执行的业务逻辑。
 */
@Service
public class LightingPolicyServiceImpl extends ServiceImpl<LightingPolicyMapper, LightingPolicy> implements LightingPolicyService {
}
