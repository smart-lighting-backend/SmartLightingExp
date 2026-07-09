package com.experiment.smartlightingexp.mqtt;

import com.experiment.smartlightingexp.entity.AlarmRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统事件 MQTT 发布 — 向前端推送告警生命周期等实时事件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemEventPublisher {

    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;

    public void publishAlarmEvent(String action, AlarmRecord alarm) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("action", action); // created / acknowledged / resolved
        event.put("alarmId", alarm.getId());
        event.put("deviceId", alarm.getDeviceId());
        event.put("type", alarm.getType());
        event.put("level", alarm.getLevel());
        event.put("status", alarm.getStatus());
        event.put("timestamp", LocalDateTime.now().toString());
        try {
            mqttPublisher.publish("system/alarms", objectMapper.writeValueAsString(event), 0);
        } catch (Exception e) {
            log.error("发布告警事件失败: {}", e.getMessage());
        }
    }
}
