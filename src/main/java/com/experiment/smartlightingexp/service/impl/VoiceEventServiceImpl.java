package com.experiment.smartlightingexp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.experiment.smartlightingexp.entity.VoiceEvent;
import com.experiment.smartlightingexp.mapper.VoiceEventMapper;
import com.experiment.smartlightingexp.service.VoiceEventService;
import org.springframework.stereotype.Service;

@Service
public class VoiceEventServiceImpl extends ServiceImpl<VoiceEventMapper, VoiceEvent> implements VoiceEventService {
}
