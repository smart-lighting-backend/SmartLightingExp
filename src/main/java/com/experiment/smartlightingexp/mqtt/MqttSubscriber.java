package com.experiment.smartlightingexp.mqtt;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.experiment.smartlightingexp.entity.ControlCommand;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.entity.Telemetry;
import com.experiment.smartlightingexp.entity.VisionEvent;
import com.experiment.smartlightingexp.entity.VoiceEvent;
import com.experiment.smartlightingexp.mapper.ControlCommandMapper;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.mapper.TelemetryMapper;
import com.experiment.smartlightingexp.mapper.VisionEventMapper;
import com.experiment.smartlightingexp.mapper.VoiceEventMapper;
import com.experiment.smartlightingexp.engine.DecisionEngine;
import com.experiment.smartlightingexp.service.AlarmRecordService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MqttSubscriber {

    private final MqttClient mqttClient;
    private final TelemetryMapper telemetryMapper;
    private final VisionEventMapper visionEventMapper;
    private final VoiceEventMapper voiceEventMapper;
    private final ObjectMapper objectMapper;
    private final DeviceMapper deviceMapper;
    private final ControlCommandMapper controlCommandMapper;
    private final DecisionEngine decisionEngine;
    private final AlarmRecordService alarmRecordService;

    @PostConstruct
    public void init() {
        mqttClient.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                log.warn("MQTT connection lost: {}", cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, org.eclipse.paho.client.mqttv3.MqttMessage message) {
                try {
                    String json = new String(message.getPayload());
                    String deviceId = topic.split("/")[1];

                    // 视觉事件
                    if (topic.endsWith("/vision/event")) {
                        VisionEvent ve = objectMapper.readValue(json, VisionEvent.class);
                        visionEventMapper.insert(ve);
                        log.info("  [{}] 👁 vision event: type={}", deviceId, ve.getEventType());
                        alarmRecordService.resolveOfflineAlarm(deviceId);
                        return;
                    }
                    // 语音事件
                    if (topic.endsWith("/voice/event")) {
                        VoiceEvent vo = objectMapper.readValue(json, VoiceEvent.class);
                        voiceEventMapper.insert(vo);
                        log.info("  [{}] 🔊 voice event: type={}", deviceId, vo.getType());
                        alarmRecordService.resolveOfflineAlarm(deviceId);
                        return;
                    }
                    // 指令确认 ACK
                    if (topic.endsWith("/command/ack")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> ack = objectMapper.readValue(json, Map.class);
                        Object cmdIdObj = ack.get("commandId");
                        Long commandId = cmdIdObj != null ? Long.valueOf(cmdIdObj.toString()) : null;
                        if (commandId != null) {
                            ControlCommand cmd = controlCommandMapper.selectById(commandId);
                            if (cmd != null && cmd.getAckAt() == null) {
                                String ackStatus = ack.get("status") != null ? ack.get("status").toString() : "ACKED";
                                cmd.setStatus(ackStatus);
                                cmd.setAckAt(LocalDateTime.now());
                                controlCommandMapper.updateById(cmd);
                                log.info("  [{}] ✅ ACK received: cmdId={}, status={}", deviceId, commandId, ackStatus);
                            }
                        }
                        return;
                    }

                    // 遥测数据
                    Telemetry telemetry = objectMapper.readValue(json, Telemetry.class);
                    telemetryMapper.insert(telemetry);
                    LocalDateTime now = LocalDateTime.now();

                    Device device = deviceMapper.selectOne(
                            Wrappers.<Device>lambdaQuery().eq(Device::getDeviceId, deviceId));
                    boolean inManual = device != null
                            && Boolean.TRUE.equals(device.getManualMode())
                            && device.getManualExpireAt() != null
                            && device.getManualExpireAt().isAfter(now);

                    if (inManual) {
                        deviceMapper.update(null,
                                Wrappers.<Device>lambdaUpdate()
                                        .eq(Device::getDeviceId, deviceId)
                                        .set(Device::getLatestData, json)
                                        .set(Device::getLastHeartbeatAt, now)
                                        .set(Device::getStatus, 1));
                        log.info("  [{}] ← MQTT received (manual mode, AI skipped) → DB inserted (id={})",
                                deviceId, telemetry.getId());
                    } else {
                        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Device> updateWrapper =
                                Wrappers.<Device>lambdaUpdate()
                                        .eq(Device::getDeviceId, deviceId)
                                        .set(Device::getLatestData, json)
                                        .set(Device::getLastHeartbeatAt, now)
                                        .set(Device::getStatus, 1);
                        if (device != null && Boolean.TRUE.equals(device.getManualMode())) {
                            updateWrapper.set(Device::getManualMode, false)
                                    .set(Device::getManualExpireAt, null);
                            log.info("  [{}] Manual mode expired, auto-cleared", deviceId);
                        }
                        deviceMapper.update(null, updateWrapper);
                        log.info("  [{}] ← MQTT received → DB inserted (id={})",
                                deviceId, telemetry.getId());
                        decisionEngine.evaluate(deviceId, telemetry);
                    }

                    alarmRecordService.resolveOfflineAlarm(deviceId);
                } catch (Exception e) {
                    log.error("  [{}] ✗ process failed: {}", topic, e.getMessage());
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        });

        try {
            mqttClient.subscribe("streetlight/+/telemetry", 1);
            mqttClient.subscribe("streetlight/+/vision/event", 1);
            mqttClient.subscribe("streetlight/+/voice/event", 1);
            mqttClient.subscribe("streetlight/+/command/ack", 1);
            log.info("MQTT subscriber ready, topics=telemetry,vision/event,voice/event,command/ack");
        } catch (MqttException e) {
            log.error("MQTT subscribe failed: {}", e.getMessage());
        }
    }
}
