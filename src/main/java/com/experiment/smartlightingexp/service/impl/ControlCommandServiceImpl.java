package com.experiment.smartlightingexp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.experiment.smartlightingexp.entity.ControlCommand;
import com.experiment.smartlightingexp.mapper.ControlCommandMapper;
import com.experiment.smartlightingexp.service.ControlCommandService;
import org.springframework.stereotype.Service;

/**
 * 控制指令 Service 实现 — 指令下发、状态追踪的业务逻辑。
 */
@Service
public class ControlCommandServiceImpl extends ServiceImpl<ControlCommandMapper, ControlCommand> implements ControlCommandService {
}
