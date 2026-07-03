package com.experiment.smartlightingexp.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.experiment.smartlightingexp.entity.AlarmRecord;
import com.experiment.smartlightingexp.mapper.AlarmRecordMapper;
import com.experiment.smartlightingexp.service.AlarmRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 告警记录 Service 实现 — 告警生成、查询和处理的业务逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlarmRecordServiceImpl extends ServiceImpl<AlarmRecordMapper, AlarmRecord> implements AlarmRecordService {

    private final AlarmRecordMapper alarmRecordMapper;

    @Override
    public AlarmRecord findActiveOfflineAlarm(String deviceId) {
        return alarmRecordMapper.selectOne(
                Wrappers.<AlarmRecord>lambdaQuery()
                        .eq(AlarmRecord::getDeviceId, deviceId)
                        .eq(AlarmRecord::getType, "OFFLINE")
                        .eq(AlarmRecord::getStatus, "ACTIVE")
                        .last("LIMIT 1"));
    }

    @Override
    public void resolveOfflineAlarm(String deviceId) {
        AlarmRecord alarm = findActiveOfflineAlarm(deviceId);
        if (alarm != null) {
            alarm.setStatus("RECOVERED");
            alarm.setRecoverAt(LocalDateTime.now());
            alarmRecordMapper.updateById(alarm);
            log.warn("  [{}] OFFLINE alarm resolved (id={})", deviceId, alarm.getId());
        }
    }
}
