package com.experiment.smartlightingexp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.experiment.smartlightingexp.entity.EnergyRecord;
import com.experiment.smartlightingexp.mapper.EnergyRecordMapper;
import com.experiment.smartlightingexp.service.EnergyRecordService;
import org.springframework.stereotype.Service;

@Service
public class EnergyRecordServiceImpl extends ServiceImpl<EnergyRecordMapper, EnergyRecord> implements EnergyRecordService {
}
