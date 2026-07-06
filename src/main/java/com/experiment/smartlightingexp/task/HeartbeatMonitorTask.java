package com.experiment.smartlightingexp.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.experiment.smartlightingexp.entity.AlarmRecord;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.mapper.AlarmRecordMapper;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 心跳监控定时任务 — 检测设备心跳超时，触发离线状态更新和告警生成。
 * <p>
 * 定期扫描所有启用设备，对比 lastHeartbeatAt 与当前时间，
 * 超过阈值（默认 300 秒）则：
 * <ol>
 *   <li>将设备 status 置为 2（离线）</li>
 *   <li>若尚无 ACTIVE 状态的 OFFLINE 告警，则插入一条</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatMonitorTask {

    private final DeviceMapper deviceMapper;
    private final AlarmRecordMapper alarmRecordMapper;

    @Value("${heartbeat.offline-threshold-seconds:300}")
    private int offlineThresholdSeconds;

    /**
     * 心跳检测主逻辑 — 应用启动后延迟 120 秒首次执行，之后每 60 秒一次。
     */
    @Scheduled(initialDelayString = "${heartbeat.startup-delay-ms:120000}",
               fixedRateString = "${heartbeat.detection-interval-ms:60000}")
    public void checkHeartbeat() {
        List<Device> devices = deviceMapper.selectList(
                Wrappers.<Device>lambdaQuery()
                        .eq(Device::getEnabled, true)
                        .eq(Device::getDeleted, false));

        if (devices.isEmpty()) {
            return;
        }

        int offlineCount = 0;
        int alarmCount = 0;

        for (Device device : devices) {
            // 跳过从未上报的设备
            if (device.getLastHeartbeatAt() == null) {
                continue;
            }

            long elapsedSeconds = Duration.between(device.getLastHeartbeatAt(), LocalDateTime.now()).getSeconds();
            if (elapsedSeconds < offlineThresholdSeconds) {
                continue;
            }

            // 标记设备离线
            deviceMapper.update(null,
                    Wrappers.<Device>lambdaUpdate()
                            .eq(Device::getDeviceId, device.getDeviceId())
                            .set(Device::getStatus, 2));
            offlineCount++;

            // 检查是否已有 ACTIVE 离线告警（去重）
            Long existingAlarmCount = alarmRecordMapper.selectCount(
                    Wrappers.<AlarmRecord>lambdaQuery()
                            .eq(AlarmRecord::getDeviceId, device.getDeviceId())
                            .eq(AlarmRecord::getType, "OFFLINE")
                            .eq(AlarmRecord::getStatus, "ACTIVE"));

            if (existingAlarmCount == 0) {
                AlarmRecord alarm = new AlarmRecord();
                alarm.setDeviceId(device.getDeviceId());
                alarm.setType("OFFLINE");
                alarm.setLevel("MAJOR");
                alarm.setStatus("ACTIVE");
                alarm.setReason("心跳中断超过 " + offlineThresholdSeconds
                        + " 秒，最后心跳时间：" + device.getLastHeartbeatAt());
                alarm.setStartAt(LocalDateTime.now());
                alarmRecordMapper.insert(alarm);
                alarmCount++;

                log.warn("设备离线 [{}], 心跳中断{}秒", device.getDeviceId(), elapsedSeconds);
            }
        }

        if (offlineCount > 0) {
            log.warn("心跳监控: {}台离线, {}条新告警", offlineCount, alarmCount);
        }
    }
}
