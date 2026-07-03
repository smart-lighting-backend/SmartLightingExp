package com.experiment.smartlightingexp.task;

// import com.experiment.smartlightingexp.service.AlarmRecordService;
// import lombok.RequiredArgsConstructor;
// import lombok.extern.slf4j.Slf4j;
// import org.springframework.scheduling.annotation.Scheduled;
// import org.springframework.stereotype.Component;

/**
 * 设备离线检测定时任务。
 *
 * 注意：离线检测功能已由 HeartbeatMonitorTask 实现（60s 周期，120s 启动延迟），
 * 此文件为冗余实现，暂时注释避免重复执行。
 *
 * 如需启用，取消 @Component 和 @Scheduled 注释即可。
 */
// @Slf4j
// @Component
// @RequiredArgsConstructor
public class DeviceOfflineMonitor {

    // private final AlarmRecordService alarmRecordService;

    // @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    // public void scanOfflineDevices() {
    //     try {
    //         alarmRecordService.scanOfflineDevices();
    //     } catch (Exception e) {
    //         log.error("Device offline scan failed: {}", e.getMessage(), e);
    //     }
    // }
}
