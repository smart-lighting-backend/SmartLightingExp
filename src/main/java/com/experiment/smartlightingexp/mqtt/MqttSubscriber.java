package com.experiment.smartlightingexp.mqtt;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.experiment.smartlightingexp.entity.ControlCommand;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.entity.Telemetry;
import com.experiment.smartlightingexp.mapper.ControlCommandMapper;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.engine.DecisionEngine;
import com.experiment.smartlightingexp.service.AlarmRecordService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final ObjectMapper objectMapper;
    private final DeviceMapper deviceMapper;
    private final ControlCommandMapper controlCommandMapper;
    private final DecisionEngine decisionEngine;
    private final AlarmRecordService alarmRecordService;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

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
                    String json = new String(message.getPayload(), StandardCharsets.UTF_8);
                    String deviceId = topic.split("/")[1];

                    // 视觉事件
                    if (topic.endsWith("/vision/event") || topic.endsWith("/voice/event")) {
                        alarmRecordService.resolveOfflineAlarm(deviceId);
                        return;
                    }
                    // 心跳
                    if (topic.endsWith("/heartbeat")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> hb = objectMapper.readValue(json, Map.class);
                        String hbDeviceId = hb.get("deviceId") != null ? hb.get("deviceId").toString() : deviceId;
                        LocalDateTime hbNow = LocalDateTime.now();
                        deviceMapper.update(null,
                                Wrappers.<Device>lambdaUpdate()
                                        .eq(Device::getDeviceId, hbDeviceId)
                                        .set(Device::getLastHeartbeatAt, hbNow)
                                        .set(Device::getStatus, 1));
                        alarmRecordService.resolveOfflineAlarm(hbDeviceId);
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
                            }
                        }
                        return;
                    }

                    // 遥测数据 — 提交到线程池处理，避免阻塞 MQTT 接收线程
                    final String telemetryJson = json;
                    final String telemetryDeviceId = deviceId;
                    executor.submit(() -> {
                        try {
                            Telemetry telemetry = objectMapper.readValue(telemetryJson, Telemetry.class);
                            log.info("遥测处理 [{}]: lux={}, temp={}°C", telemetryDeviceId, telemetry.getIlluminance(), telemetry.getTemperature());
                            LocalDateTime now = LocalDateTime.now();

                            Device device = deviceMapper.selectOne(
                                    Wrappers.<Device>lambdaQuery().eq(Device::getDeviceId, telemetryDeviceId));
                            boolean inManual = device != null
                                    && Boolean.TRUE.equals(device.getManualMode())
                                    && device.getManualExpireAt() != null
                                    && device.getManualExpireAt().isAfter(now);

                            if (inManual) {
                                deviceMapper.update(null,
                                        Wrappers.<Device>lambdaUpdate()
                                                .eq(Device::getDeviceId, telemetryDeviceId)
                                                .set(Device::getLatestData, telemetryJson)
                                                .set(Device::getLastHeartbeatAt, now)
                                                .set(Device::getStatus, 1));
                            } else {
                                com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Device> updateWrapper =
                                        Wrappers.<Device>lambdaUpdate()
                                                .eq(Device::getDeviceId, telemetryDeviceId)
                                                .set(Device::getLatestData, telemetryJson)
                                                .set(Device::getLastHeartbeatAt, now)
                                                .set(Device::getStatus, 1);
                                if (device != null && Boolean.TRUE.equals(device.getManualMode())) {
                                    updateWrapper.set(Device::getManualMode, false)
                                            .set(Device::getManualExpireAt, null);
                                }
                                deviceMapper.update(null, updateWrapper);
                                decisionEngine.evaluate(telemetryDeviceId, telemetry);
                            }

                            alarmRecordService.resolveOfflineAlarm(telemetryDeviceId);
                        } catch (Exception e) {
                            log.error("遥测处理失败 [{}]: {}", telemetryDeviceId, e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    log.error("MQTT消息处理失败 [{}]: {}", topic, e.getMessage());
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        });

        try {
            mqttClient.subscribe("streetlight/+/heartbeat", 1);
            mqttClient.subscribe("streetlight/+/telemetry", 1);
            mqttClient.subscribe("streetlight/+/vision/event", 1);
            mqttClient.subscribe("streetlight/+/voice/event", 1);
            mqttClient.subscribe("streetlight/+/command/ack", 1);
            log.info("MQTT subscriber ready, topics=heartbeat,telemetry,vision/event,voice/event,command/ack");
        } catch (MqttException e) {
            log.error("MQTT subscribe failed: {}", e.getMessage());
        }
    }
}
