package com.experiment.smartlightingexp.mqtt;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.experiment.smartlightingexp.entity.ControlCommand;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.entity.Telemetry;
import com.experiment.smartlightingexp.mapper.ControlCommandMapper;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.mapper.TelemetryMapper;
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

                    // command/ack 路由：设备回复指令确认
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

                    // telemetry topic
                    Telemetry telemetry = objectMapper.readValue(json, Telemetry.class);
                    telemetryMapper.insert(telemetry);
                    LocalDateTime now = LocalDateTime.now();

                    // 检查设备是否处于手动控制模式
                    Device device = deviceMapper.selectOne(
                            Wrappers.<Device>lambdaQuery().eq(Device::getDeviceId, deviceId));
                    boolean inManual = device != null
                            && Boolean.TRUE.equals(device.getManualMode())
                            && device.getManualExpireAt() != null
                            && device.getManualExpireAt().isAfter(now);

                    if (inManual) {
                        // 手动模式：只更新心跳和在线状态，不覆盖 latestData，不触发 AI
                        deviceMapper.update(null,
                                Wrappers.<Device>lambdaUpdate()
                                        .eq(Device::getDeviceId, deviceId)
                                        .set(Device::getLastHeartbeatAt, now)
                                        .set(Device::getStatus, 1));
                        log.info("  [{}] ← MQTT received (manual mode, AI skipped) → DB inserted (id={})",
                                deviceId, telemetry.getId());
                    } else {
                        // 自动模式：正常更新 latestData + 触发 AI 策略引擎
                        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Device> updateWrapper =
                                Wrappers.<Device>lambdaUpdate()
                                        .eq(Device::getDeviceId, deviceId)
                                        .set(Device::getLatestData, json)
                                        .set(Device::getLastHeartbeatAt, now)
                                        .set(Device::getStatus, 1);
                        // 手动模式已过期则自动清除标记
                        if (device != null && Boolean.TRUE.equals(device.getManualMode())) {
                            updateWrapper.set(Device::getManualMode, false)
                                    .set(Device::getManualExpireAt, null);
                            log.info("  [{}] Manual mode expired, auto-cleared", deviceId);
                        }
                        deviceMapper.update(null, updateWrapper);
                        log.info("  [{}] ← MQTT received → DB inserted (id={})",
                                deviceId, telemetry.getId());
                        // 触发 AI 策略引擎评估
                        decisionEngine.evaluate(deviceId, telemetry);
                    }

                    // 自动恢复离线告警（设备重新上报说明已恢复在线）
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
            mqttClient.subscribe("streetlight/+/command/ack", 1);
            log.info("MQTT subscriber ready, topics=telemetry,command/ack");
        } catch (MqttException e) {
            log.error("MQTT subscribe failed: {}", e.getMessage());
        }
    }
}
