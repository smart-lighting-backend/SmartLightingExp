package com.experiment.smartlightingexp.mqtt;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.entity.Telemetry;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.mapper.TelemetryMapper;
import com.experiment.smartlightingexp.engine.DecisionEngine;
import com.experiment.smartlightingexp.service.AlarmRecordService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class MqttSubscriber {

    private final MqttClient mqttClient;
    private final TelemetryMapper telemetryMapper;
    private final ObjectMapper objectMapper;
    private final DeviceMapper deviceMapper;
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
                    Telemetry telemetry = objectMapper.readValue(json, Telemetry.class);
                    String deviceId = topic.split("/")[1];
                    if (!StringUtils.hasText(telemetry.getDeviceId())) {
                        telemetry.setDeviceId(deviceId);
                    }
                    telemetryMapper.insert(telemetry);
                    deviceMapper.update(null,
                            Wrappers.<Device>lambdaUpdate()
                                    .eq(Device::getDeviceId, deviceId)
                                    .set(Device::getLatestData, json));
                    alarmRecordService.markDeviceOnline(deviceId, telemetry.getCollectedAt());
                    log.info("  [{}] ← MQTT received → DB inserted (id={})",
                            deviceId, telemetry.getId());

                    // 触发 AI 策略引擎评估
                    decisionEngine.evaluate(deviceId, telemetry);
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
            log.info("MQTT subscriber ready, topic=streetlight/+/telemetry");
        } catch (MqttException e) {
            log.error("MQTT subscribe failed: {}", e.getMessage());
        }
    }
}
