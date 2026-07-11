package com.experiment.smartlightingexp.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.experiment.smartlightingexp.entity.*;
import com.experiment.smartlightingexp.mapper.*;
import com.experiment.smartlightingexp.mqtt.SystemEventPublisher;
import com.experiment.smartlightingexp.service.AlarmRecordService;
import com.experiment.smartlightingexp.tdengine.TelemetryDao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 预测性维护 — 设备健康评分定时任务。
 * 每天凌晨 2:00 对每台已启用设备综合 4 个维度打分 (0-100)：
 *   ① 离线频次 (30%) — alarm_record 近 7 天离线次数
 *   ② 通信质量 (25%) — telemetry 近 24h 上报间隔标准差
 *   ③ 指令响应率 (25%) — control_command 近 7 天 ACK 率
 *   ④ 传感器异常率 (20%) — latestData 各字段是否在合理范围
 *
 * 健康分低于阈值 (60) 时自动产生 HEALTH_LOW 告警，回升后自动恢复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HealthScoreTask {

    private static final int HEALTH_THRESHOLD = 50;
    private static final String ALARM_TYPE_HEALTH = "HEALTH_LOW";
    private static final String ALARM_LEVEL_WARNING = "WARNING";

    private final DeviceMapper deviceMapper;
    private final AlarmRecordMapper alarmRecordMapper;
    private final TelemetryMapper telemetryMapper;
    private final TelemetryDao telemetryDao;
    private final ControlCommandMapper controlCommandMapper;
    private final ObjectMapper objectMapper;
    private final AlarmRecordService alarmRecordService;
    private final SystemEventPublisher systemEventPublisher;

    private final Random random = new Random();

    /**
     * 每日凌晨 2:00 全量计算健康分（深度诊断）。
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void computeAllDaily() {
        computeAll();
    }

    /**
     * 每 30 分钟增量刷新健康分（启动后 2 分钟首次执行），
     * 确保列表页 health_score 不会因定时周期过长而陈旧。
     */
    @Scheduled(fixedRate = 30 * 60 * 1000, initialDelay = 2 * 60 * 1000)
    public void computeAllPeriodic() {
        computeAll();
    }

    private void computeAll() {
        List<Device> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<Device>()
                        .eq(Device::getEnabled, true)
                        .eq(Device::getDeleted, false));
        if (devices.isEmpty()) return;

        int count = 0;
        for (Device device : devices) {
            try {
                int offlineScore   = calcOfflineScore(device.getDeviceId());
                int commScore      = calcCommunicationScore(device.getDeviceId());
                int responseScore  = calcResponseScore(device.getDeviceId());
                int sensorScore    = calcSensorScore(device);
                int total = (int) Math.round(
                        0.30 * offlineScore + 0.25 * commScore + 0.25 * responseScore + 0.20 * sensorScore);

                device.setHealthScore(BigDecimal.valueOf(total));
                deviceMapper.updateById(device);

                // 健康分告警：低于阈值产生（去重），回升则自动恢复
                if (total < HEALTH_THRESHOLD) {
                    if (alarmRecordService.findActiveHealthAlarm(device.getDeviceId()) == null) {
                        AlarmRecord alarm = new AlarmRecord();
                        alarm.setDeviceId(device.getDeviceId());
                        alarm.setType(ALARM_TYPE_HEALTH);
                        alarm.setLevel(ALARM_LEVEL_WARNING);
                        alarm.setStatus("ACTIVE");
                        alarm.setReason("健康分降至 " + total + "，低于阈值 " + HEALTH_THRESHOLD);
                        alarm.setStartAt(LocalDateTime.now());
                        alarmRecordMapper.insert(alarm);
                        systemEventPublisher.publishAlarmEvent("created", alarm);
                        log.warn("[{}] HEALTH_LOW alarm created (score={})", device.getDeviceId(), total);
                    }
                } else {
                    alarmRecordService.resolveHealthAlarm(device.getDeviceId());
                }

                count++;
            } catch (Exception e) {
                log.error("健康分计算失败 [{}]: {}", device.getDeviceId(), e.getMessage());
            }
        }
        log.info("健康分计算完成: {}/{} 台设备", count, devices.size());
    }

    // ───────────── 维度 1：离线频次 (30%) ─────────────
    int calcOfflineScore(String deviceId) {
        long count = alarmRecordMapper.selectCount(
                new LambdaQueryWrapper<AlarmRecord>()
                        .eq(AlarmRecord::getDeviceId, deviceId)
                        .eq(AlarmRecord::getType, "OFFLINE")
                        .ge(AlarmRecord::getStartAt, LocalDateTime.now().minusDays(7)));
        if (count == 0) return 100;
        if (count == 1) return 80;
        if (count == 2) return 60;
        if (count == 3) return 40;
        return 20;
    }

    // ───────────── 维度 2：通信质量 (25%) ─────────────
    int calcCommunicationScore(String deviceId) {
        List<Telemetry> list = telemetryDao.query24h(deviceId);
        if (list == null || list.size() < 3) return 0;

        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < list.size(); i++) {
            LocalDateTime prev = list.get(i - 1).getCollectedAt();
            LocalDateTime curr = list.get(i).getCollectedAt();
            if (prev == null || curr == null) continue;
            Duration d = Duration.between(prev, curr);
            gaps.add((double) Math.abs(d.getSeconds() - 300));
        }
        if (gaps.isEmpty()) return 0;
        double avgDeviation = gaps.stream().mapToDouble(Double::doubleValue).average().orElse(999);
        if (avgDeviation < 30)  return 100;
        if (avgDeviation < 60)  return 80;
        if (avgDeviation < 120) return 60;
        return 40;
    }

    // ───────────── 维度 3：指令响应率 (25%) ─────────────
    int calcResponseScore(String deviceId) {
        List<ControlCommand> all = controlCommandMapper.selectList(
                new LambdaQueryWrapper<ControlCommand>()
                        .eq(ControlCommand::getDeviceId, deviceId)
                        .ge(ControlCommand::getIssuedAt, LocalDateTime.now().minusDays(7)));
        if (all.isEmpty()) return 100; // 无指令 = 中性

        long acked = all.stream().filter(c -> c.getAckAt() != null).count();
        double rate = (double) acked / all.size();
        if (rate >= 1.0)  return 100;
        if (rate >= 0.8)  return 80;
        if (rate >= 0.5)  return 60;
        return 30;
    }

    // ───────────── 维度 4：传感器异常率 (20%) ─────────────
    int calcSensorScore(Device device) {
        if (device.getLatestData() == null || device.getLatestData().isBlank()) return 0;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(device.getLatestData(), Map.class);
            int abnormal = 0;
            int total = 0;
            abnormal += checkRange(data, "illuminance", 0, 2000);    total++;
            abnormal += checkRange(data, "temperature", -10, 50);    total++;
            abnormal += checkRange(data, "humidity", 0, 100);        total++;
            abnormal += checkRange(data, "pm25", 0, 500);            total++;
            abnormal += checkRange(data, "aqi", 0, 500);             total++;
            if (total == 0) return 100;
            double ratio = (double) abnormal / total;
            if (ratio == 0)    return 100;
            if (ratio <= 0.2)  return 70;
            if (ratio <= 0.5)  return 40;
            return 10;
        } catch (JsonProcessingException e) {
            return 0;
        }
    }

    private int checkRange(Map<String, Object> data, String key, double min, double max) {
        Object val = data.get(key);
        if (val == null) return 1;
        try {
            double d = Double.parseDouble(val.toString());
            return (d >= min && d <= max) ? 0 : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
