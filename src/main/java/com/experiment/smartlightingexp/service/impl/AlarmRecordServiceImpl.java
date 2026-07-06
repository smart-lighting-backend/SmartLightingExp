package com.experiment.smartlightingexp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.experiment.smartlightingexp.common.BusinessException;
import com.experiment.smartlightingexp.dto.AlarmPageRequest;
import com.experiment.smartlightingexp.entity.AlarmRecord;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.mapper.AlarmRecordMapper;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.service.AlarmRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警记录 Service 实现 — 告警生成、查询和处理的业务逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlarmRecordServiceImpl extends ServiceImpl<AlarmRecordMapper, AlarmRecord> implements AlarmRecordService {

    private static final long MAX_PAGE_SIZE = 100L;
    private static final int DEVICE_STATUS_DISABLED = 0;
    private static final int DEVICE_STATUS_OFFLINE = 2;
    private static final String ALARM_TYPE_OFFLINE = "OFFLINE";
    private static final String ALARM_LEVEL_MAJOR = "MAJOR";
    private static final String ALARM_STATUS_ACTIVE = "ACTIVE";
    private static final String ALARM_STATUS_RECOVERED = "RECOVERED";

    private final AlarmRecordMapper alarmRecordMapper;
    private final DeviceMapper deviceMapper;

    @Value("${heartbeat.offline-threshold-seconds:300}")
    private int offlineThresholdSeconds;

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
    public Page<AlarmRecord> pageAlarms(AlarmPageRequest request) {
        long pageNum = normalizePageNum(request.getPageNum());
        long pageSize = normalizePageSize(request.getPageSize());

        LambdaQueryWrapper<AlarmRecord> wrapper = Wrappers.lambdaQuery();
        if (StringUtils.hasText(request.getDeviceId())) {
            wrapper.eq(AlarmRecord::getDeviceId, request.getDeviceId().trim());
        }
        if (StringUtils.hasText(request.getType())) {
            wrapper.eq(AlarmRecord::getType, request.getType().trim());
        }
        if (StringUtils.hasText(request.getLevel())) {
            wrapper.eq(AlarmRecord::getLevel, request.getLevel().trim());
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(AlarmRecord::getStatus, request.getStatus().trim());
        }
        if (request.getStartTime() != null) {
            wrapper.ge(AlarmRecord::getStartAt, request.getStartTime());
        }
        if (request.getEndTime() != null) {
            wrapper.le(AlarmRecord::getStartAt, request.getEndTime());
        }

        wrapper.orderByDesc(AlarmRecord::getStartAt).orderByDesc(AlarmRecord::getId);
        return page(new Page<>(pageNum, pageSize), wrapper);
    }

    @Override
    public AlarmRecord getAlarmDetail(Long id) {
        if (id == null) {
            throw new BusinessException(400, "Alarm id cannot be empty");
        }
        AlarmRecord alarm = getById(id);
        if (alarm == null) {
            throw new BusinessException(404, "Alarm record not found");
        }
        return alarm;
    }

    @Override
    public void scanOfflineDevices() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime offlineBefore = now.minusSeconds(offlineThresholdSeconds);
        List<Device> devices = deviceMapper.selectList(
                Wrappers.<Device>lambdaQuery()
                        .eq(Device::getEnabled, true)
                        .eq(Device::getDeleted, false)
                        .ne(Device::getStatus, DEVICE_STATUS_DISABLED));

        int offlineCount = 0;
        int alarmCount = 0;
        for (Device device : devices) {
            LocalDateTime lastHeartbeatAt = device.getLastHeartbeatAt();
            if (lastHeartbeatAt != null && lastHeartbeatAt.isAfter(offlineBefore)) {
                continue;
            }

            deviceMapper.update(null,
                    Wrappers.<Device>lambdaUpdate()
                            .eq(Device::getId, device.getId())
                            .set(Device::getStatus, DEVICE_STATUS_OFFLINE));
            offlineCount++;

            if (findActiveOfflineAlarm(device.getDeviceId()) != null) {
                continue;
            }

            AlarmRecord alarm = new AlarmRecord();
            alarm.setDeviceId(device.getDeviceId());
            alarm.setType(ALARM_TYPE_OFFLINE);
            alarm.setLevel(ALARM_LEVEL_MAJOR);
            alarm.setStatus(ALARM_STATUS_ACTIVE);
            alarm.setReason(buildOfflineReason(lastHeartbeatAt));
            alarm.setStartAt(now);
            alarmRecordMapper.insert(alarm);
            alarmCount++;

            log.warn("[{}] OFFLINE detected, lastHeartbeat={}", device.getDeviceId(), lastHeartbeatAt);
        }

        if (offlineCount > 0) {
            log.info("DeviceOfflineMonitor: {} offline, {} new alarms", offlineCount, alarmCount);
        }
    }

    @Override
    public void resolveOfflineAlarm(String deviceId) {
        AlarmRecord alarm = findActiveOfflineAlarm(deviceId);
        if (alarm != null) {
            alarm.setStatus(ALARM_STATUS_RECOVERED);
            alarm.setRecoverAt(LocalDateTime.now());
            alarmRecordMapper.updateById(alarm);
            log.warn("  [{}] OFFLINE alarm resolved (id={})", deviceId, alarm.getId());
        }
    }

    @Override
    public AlarmRecord findActiveHealthAlarm(String deviceId) {
        return alarmRecordMapper.selectOne(
                Wrappers.<AlarmRecord>lambdaQuery()
                        .eq(AlarmRecord::getDeviceId, deviceId)
                        .eq(AlarmRecord::getType, "HEALTH_LOW")
                        .eq(AlarmRecord::getStatus, ALARM_STATUS_ACTIVE)
                        .last("LIMIT 1"));
    }

    @Override
    public void resolveHealthAlarm(String deviceId) {
        AlarmRecord alarm = findActiveHealthAlarm(deviceId);
        if (alarm != null) {
            alarm.setStatus(ALARM_STATUS_RECOVERED);
            alarm.setRecoverAt(LocalDateTime.now());
            alarmRecordMapper.updateById(alarm);
            log.info("[{}] HEALTH_LOW alarm resolved (id={})", deviceId, alarm.getId());
        }
    }

    private String buildOfflineReason(LocalDateTime lastHeartbeatAt) {
        if (lastHeartbeatAt == null) {
            return "No heartbeat reported, judged offline";
        }
        return "Heartbeat timeout over " + offlineThresholdSeconds
                + " seconds, last heartbeat: " + lastHeartbeatAt;
    }

    private long normalizePageNum(Long pageNum) {
        if (pageNum == null || pageNum < 1) {
            return 1L;
        }
        return pageNum;
    }

    private long normalizePageSize(Long pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 10L;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
