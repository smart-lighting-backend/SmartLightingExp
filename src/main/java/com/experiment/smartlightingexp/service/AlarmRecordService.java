package com.experiment.smartlightingexp.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.experiment.smartlightingexp.dto.AlarmPageRequest;
import com.experiment.smartlightingexp.entity.AlarmRecord;

/**
 * 告警记录 Service — 告警生成、查询和处理的业务逻辑接口。
 */
public interface AlarmRecordService extends IService<AlarmRecord> {

    /**
     * 查询指定设备是否存在 ACTIVE 状态的 OFFLINE 告警。
     *
     * @param deviceId 设备编号
     * @return 告警记录，或 null
     */
    AlarmRecord findActiveOfflineAlarm(String deviceId);

    Page<AlarmRecord> pageAlarms(AlarmPageRequest request);

    AlarmRecord getAlarmDetail(Long id);

    void scanOfflineDevices();

    /**
     * 恢复指定设备的 OFFLINE 告警（置为 RECOVERED）。
     * 若设备有 ACTIVE 的离线告警，自动关闭。
     *
     * @param deviceId 设备编号
     */
    void resolveOfflineAlarm(String deviceId);
}
