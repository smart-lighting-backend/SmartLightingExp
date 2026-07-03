package com.experiment.smartlightingexp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警记录 Service 实现 — 告警生成、查询和处理的业务逻辑。
 */
@Service
@RequiredArgsConstructor
public class AlarmRecordServiceImpl extends ServiceImpl<AlarmRecordMapper, AlarmRecord> implements AlarmRecordService {

    private static final long MAX_PAGE_SIZE = 100L;
    private static final long OFFLINE_THRESHOLD_MINUTES = 5L;
    private static final int DEVICE_STATUS_DISABLED = 0;
    private static final int DEVICE_STATUS_ONLINE = 1;
    private static final int DEVICE_STATUS_OFFLINE = 2;
    private static final String ALARM_TYPE_OFFLINE = "OFFLINE";
    private static final String ALARM_LEVEL_MAJOR = "MAJOR";
    private static final String ALARM_STATUS_ACTIVE = "ACTIVE";
    private static final String ALARM_STATUS_ACKNOWLEDGED = "ACKNOWLEDGED";
    private static final String ALARM_STATUS_RECOVERED = "RECOVERED";

    private final DeviceMapper deviceMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void scanOfflineDevices() {
        LocalDateTime offlineBefore = LocalDateTime.now().minusMinutes(OFFLINE_THRESHOLD_MINUTES);
        List<Device> devices = deviceMapper.selectList(new LambdaQueryWrapper<Device>()
                .eq(Device::getDeleted, false)
                .eq(Device::getEnabled, true)
                .ne(Device::getStatus, DEVICE_STATUS_DISABLED)
                .and(query -> query
                        .isNull(Device::getLastHeartbeatAt)
                        .or()
                        .lt(Device::getLastHeartbeatAt, offlineBefore)));

        for (Device device : devices) {
            markDeviceOffline(device);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markDeviceOnline(String deviceId, LocalDateTime heartbeatAt) {
        LocalDateTime onlineAt = heartbeatAt == null ? LocalDateTime.now() : heartbeatAt;
        deviceMapper.update(null, new LambdaUpdateWrapper<Device>()
                .eq(Device::getDeviceId, deviceId)
                .set(Device::getStatus, DEVICE_STATUS_ONLINE)
                .set(Device::getLastHeartbeatAt, onlineAt));

        update(new LambdaUpdateWrapper<AlarmRecord>()
                .eq(AlarmRecord::getDeviceId, deviceId)
                .eq(AlarmRecord::getType, ALARM_TYPE_OFFLINE)
                .in(AlarmRecord::getStatus, ALARM_STATUS_ACTIVE, ALARM_STATUS_ACKNOWLEDGED)
                .set(AlarmRecord::getStatus, ALARM_STATUS_RECOVERED)
                .set(AlarmRecord::getRecoverAt, onlineAt));
    }

    @Override
    public Page<AlarmRecord> pageAlarms(AlarmPageRequest request) {
        long pageNum = normalizePageNum(request.getPageNum());
        long pageSize = normalizePageSize(request.getPageSize());

        LambdaQueryWrapper<AlarmRecord> wrapper = new LambdaQueryWrapper<>();
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
        AlarmRecord alarmRecord = getById(id);
        if (alarmRecord == null) {
            throw new BusinessException(404, "告警记录不存在");
        }
        return alarmRecord;
    }

    private void markDeviceOffline(Device device) {
        deviceMapper.update(null, new LambdaUpdateWrapper<Device>()
                .eq(Device::getDeviceId, device.getDeviceId())
                .set(Device::getStatus, DEVICE_STATUS_OFFLINE));

        Long activeCount = count(new LambdaQueryWrapper<AlarmRecord>()
                .eq(AlarmRecord::getDeviceId, device.getDeviceId())
                .eq(AlarmRecord::getType, ALARM_TYPE_OFFLINE)
                .in(AlarmRecord::getStatus, ALARM_STATUS_ACTIVE, ALARM_STATUS_ACKNOWLEDGED));
        if (activeCount != null && activeCount > 0) {
            return;
        }

        AlarmRecord alarmRecord = new AlarmRecord();
        alarmRecord.setDeviceId(device.getDeviceId());
        alarmRecord.setType(ALARM_TYPE_OFFLINE);
        alarmRecord.setLevel(ALARM_LEVEL_MAJOR);
        alarmRecord.setStatus(ALARM_STATUS_ACTIVE);
        alarmRecord.setReason("设备未上报心跳，判定为离线");
        alarmRecord.setStartAt(LocalDateTime.now());
        save(alarmRecord);
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
