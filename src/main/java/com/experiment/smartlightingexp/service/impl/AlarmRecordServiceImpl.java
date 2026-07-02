package com.experiment.smartlightingexp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.experiment.smartlightingexp.entity.AlarmRecord;
import com.experiment.smartlightingexp.mapper.AlarmRecordMapper;
import com.experiment.smartlightingexp.service.AlarmRecordService;
import org.springframework.stereotype.Service;

/**
 * 告警记录 Service 实现 — 告警生成、查询和处理的业务逻辑。
 */
@Service
public class AlarmRecordServiceImpl extends ServiceImpl<AlarmRecordMapper, AlarmRecord> implements AlarmRecordService {
}
