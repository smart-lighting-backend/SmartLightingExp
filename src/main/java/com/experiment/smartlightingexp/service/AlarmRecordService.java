package com.experiment.smartlightingexp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.experiment.smartlightingexp.dto.AlarmPageRequest;
import com.experiment.smartlightingexp.entity.AlarmRecord;

import java.time.LocalDateTime;

/**
 * 告警记录 Service — 告警生成、查询和处理的业务逻辑接口。
 */
public interface AlarmRecordService extends IService<AlarmRecord> {

    void scanOfflineDevices();

    void markDeviceOnline(String deviceId, LocalDateTime heartbeatAt);

    Page<AlarmRecord> pageAlarms(AlarmPageRequest request);

    AlarmRecord getAlarmDetail(Long id);
}
