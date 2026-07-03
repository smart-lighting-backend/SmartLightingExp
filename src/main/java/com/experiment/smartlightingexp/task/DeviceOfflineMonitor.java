package com.experiment.smartlightingexp.task;

import com.experiment.smartlightingexp.service.AlarmRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceOfflineMonitor {

    private final AlarmRecordService alarmRecordService;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void scanOfflineDevices() {
        try {
            alarmRecordService.scanOfflineDevices();
        } catch (Exception e) {
            log.error("Device offline scan failed: {}", e.getMessage(), e);
        }
    }
}
