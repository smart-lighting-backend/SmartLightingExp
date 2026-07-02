package com.experiment.smartlightingexp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.experiment.smartlightingexp.entity.VisionEvent;
import com.experiment.smartlightingexp.mapper.VisionEventMapper;
import com.experiment.smartlightingexp.service.VisionEventService;
import org.springframework.stereotype.Service;

@Service
public class VisionEventServiceImpl extends ServiceImpl<VisionEventMapper, VisionEvent> implements VisionEventService {
}
