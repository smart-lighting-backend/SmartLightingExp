package com.experiment.smartlightingexp.mqtt;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.experiment.smartlightingexp.config.MqttProperties;
import com.experiment.smartlightingexp.entity.AlarmRecord;
import com.experiment.smartlightingexp.entity.ControlCommand;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.entity.Telemetry;
import com.experiment.smartlightingexp.mapper.AlarmRecordMapper;
import com.experiment.smartlightingexp.mapper.ControlCommandMapper;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.engine.DecisionEngine;
import com.experiment.smartlightingexp.service.AlarmRecordService;
import com.experiment.smartlightingexp.util.SensorValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MqttSubscriber {

    private final MqttClient mqttClient;
    private final MqttProperties mqttProperties;
    private final ObjectMapper objectMapper;
    private final DeviceMapper deviceMapper;
    private final ControlCommandMapper controlCommandMapper;
    private final AlarmRecordMapper alarmRecordMapper;
    private final SystemEventPublisher systemEventPublisher;
    private final DecisionEngine decisionEngine;
    private final AlarmRecordService alarmRecordService;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    /** 遥测异常连续计数：deviceId → FaultCounter */
    private final ConcurrentHashMap<String, FaultCounter> faultCounters = new ConcurrentHashMap<>();

    /** 连续异常次数阈值，达到后产生 FAULT 告警 */
    private static final int FAULT_ABNORMAL_THRESHOLD = 2;
    /** 连续正常次数阈值，达到后恢复 FAULT 告警 */
    private static final int FAULT_NORMAL_THRESHOLD = 2;

    private static class FaultCounter {
        int abnormalCount = 0;
        int normalCount = 0;
        List<SensorValidator.AbnormalField> lastAbnormalFields;
    }

    @PostConstruct
    public void init() {
        mqttClient.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                log.info("MQTT connected (reconnect={}), server={}", reconnect, serverURI);
                // 必须在新线程里订阅，否则会阻塞 Paho 内部回调线程导致 publish() 死锁
                new Thread(() -> subscribeTopics(), "mqtt-resubscribe").start();
            }

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

                            // ─── 传感器异常检测 → FAULT 告警 ───
                            detectFault(telemetryDeviceId, telemetry, now);

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

        subscribeTopics();
    }

    private void subscribeTopics() {
        try {
            String prefix = mqttProperties.getTopicPrefix();
            mqttClient.subscribe(prefix + "/+/heartbeat", 1);
            mqttClient.subscribe(prefix + "/+/telemetry", 1);
            mqttClient.subscribe(prefix + "/+/vision/event", 1);
            mqttClient.subscribe(prefix + "/+/voice/event", 1);
            mqttClient.subscribe(prefix + "/+/command/ack", 1);
            log.info("MQTT subscriber ready, topics=heartbeat,telemetry,vision/event,voice/event,command/ack");
        } catch (MqttException e) {
            log.error("MQTT subscribe failed: {}", e.getMessage());
        }
    }

    /**
     * 遥测传感器异常检测 → FAULT 告警（带连续计数防抖）。
     * 连续 {@value #FAULT_ABNORMAL_THRESHOLD} 次异常触发告警，
     * 连续 {@value #FAULT_NORMAL_THRESHOLD} 次正常自动恢复。
     */
    private void detectFault(String deviceId, Telemetry telemetry, LocalDateTime now) {
        Map<String, Object> fieldValues = SensorValidator.extractFieldValues(telemetry);
        List<SensorValidator.AbnormalField> abnormalFields = SensorValidator.validate(fieldValues);

        FaultCounter counter = faultCounters.computeIfAbsent(deviceId, k -> new FaultCounter());

        if (!abnormalFields.isEmpty()) {
            counter.abnormalCount++;
            counter.normalCount = 0;
            counter.lastAbnormalFields = abnormalFields;

            log.warn("[{}] 遥测异常 (连续{}/{}): {}", deviceId,
                    counter.abnormalCount, FAULT_ABNORMAL_THRESHOLD,
                    abnormalFields.stream().map(SensorValidator.AbnormalField::description)
                            .collect(Collectors.joining("; ")));

            if (counter.abnormalCount >= FAULT_ABNORMAL_THRESHOLD
                    && alarmRecordService.findActiveFaultAlarm(deviceId) == null) {
                String reason = abnormalFields.stream()
                        .map(SensorValidator.AbnormalField::description)
                        .collect(Collectors.joining("; "));
                AlarmRecord alarm = new AlarmRecord();
                alarm.setDeviceId(deviceId);
                alarm.setType("FAULT");
                alarm.setLevel("CRITICAL");
                alarm.setStatus("ACTIVE");
                alarm.setReason("传感器数据异常: " + reason);
                alarm.setStartAt(now);
                alarmRecordMapper.insert(alarm);
                systemEventPublisher.publishAlarmEvent("created", alarm);
                log.warn("[{}] FAULT alarm created: {}", deviceId, reason);
            }
        } else {
            counter.abnormalCount = 0;
            if (counter.normalCount < FAULT_NORMAL_THRESHOLD) {
                counter.normalCount++;
            }
            if (counter.normalCount >= FAULT_NORMAL_THRESHOLD) {
                alarmRecordService.resolveFaultAlarm(deviceId);
            }
        }
    }
}
