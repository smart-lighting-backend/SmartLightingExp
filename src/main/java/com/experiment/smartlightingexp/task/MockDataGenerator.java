package com.experiment.smartlightingexp.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.experiment.smartlightingexp.config.MqttProperties;
import com.experiment.smartlightingexp.entity.ControlCommand;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.mapper.ControlCommandMapper;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.mqtt.MqttPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mock.enabled", havingValue = "true")
public class MockDataGenerator {

    private final DeviceMapper deviceMapper;
    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;
    private final MqttProperties mqttProperties;
    private final ControlCommandMapper controlCommandMapper;

    private final Random random = new Random();

    /**
     * 模拟数据生成任务 — 启动 20s 后首次执行，之后每 5 分钟一次。
     * 查询数据库中所有启用的设备，为每台设备生成随机遥测数据，
     * 通过 MQTT 发布到 streetlight/{deviceId}/telemetry，
     * 由 {@link com.experiment.smartlightingexp.mqtt.MqttSubscriber} 订阅后写入数据库。
     */
    @Scheduled(initialDelay = 20000, fixedRate = 300000)
    public void generateData() {
        // 只查询已启用且未删除的设备
        LambdaQueryWrapper<Device> query = new LambdaQueryWrapper<Device>()
                .eq(Device::getEnabled, true)
                .eq(Device::getDeleted, false);
        List<Device> devices = deviceMapper.selectList(query);
        if (devices.isEmpty()) {
            return;
        }

        int successCount = 0;
        for (Device device : devices) {
            int illuminance = random.nextInt(2000);   // 光照强度（lux）
            double temperature = 15 + (40 - 15) * random.nextDouble();  // 温度（℃）
            int humidity = 40 + random.nextInt(50);       // 湿度（%）
            int pm25 = 10 + random.nextInt(140);          // PM2.5 浓度（μg/m³）
            int aqi = random.nextInt(200);                // 空气质量指数
            int pir = random.nextInt(2);                  // 人体红外检测 0-无人 1-有人
            int trafficFlow = random.nextInt(50);         // 车流量（辆/分钟）

            Map<String, Object> data = new HashMap<>();
            data.put("deviceId", device.getDeviceId());   // 设备编号
            data.put("illuminance", BigDecimal.valueOf(illuminance));   // 光照强度
            data.put("temperature", BigDecimal.valueOf(temperature).setScale(2, RoundingMode.HALF_UP));  // 温度
            data.put("humidity", BigDecimal.valueOf(humidity));         // 湿度
            data.put("pm25", BigDecimal.valueOf(pm25));                 // PM2.5
            data.put("aqi", aqi);                        // AQI
            data.put("pir", pir);                         // 人体红外
            data.put("trafficFlow", trafficFlow);         // 车流量
            data.put("collectedAt", LocalDateTime.now().toString());    // 采集时间

            try {
                String json = objectMapper.writeValueAsString(data);
                String topic = mqttProperties.getTopicPrefix() + "/" + device.getDeviceId() + "/telemetry";
                mqttPublisher.publish(topic, json, 0);
                successCount++;
            } catch (Exception e) {
                log.error("遥测发布失败 [{}]: {}", device.getDeviceId(), e.getMessage());
            }

            // 视觉事件生成：基于传感器数据触发
            if (pir == 1 && random.nextDouble() < 0.3) {
                publishVisionEvent(device.getDeviceId(), "行人检测", 0.70 + 0.29 * random.nextDouble());
            }
            if (trafficFlow > 10 && random.nextDouble() < 0.4) {
                publishVisionEvent(device.getDeviceId(), "车辆通行", 0.70 + 0.29 * random.nextDouble());
            }
            if (random.nextDouble() < 0.05) {
                String rareType = random.nextBoolean() ? "异常停车" : "危险场景";
                publishVisionEvent(device.getDeviceId(), rareType, 0.60 + 0.39 * random.nextDouble());
            }

            // 语音事件生成：10% 随机概率
            if (random.nextDouble() < 0.1) {
                publishVoiceEvent(device.getDeviceId());
            }
        }
        log.info("Mock遥测: {}/{} 台设备已发布", successCount, devices.size());

        simulateAcks(devices);
    }

    /**
     * 心跳模拟 — 启动 10s 后首次执行，之后每 30 秒一次。
     * 为每台已启用设备发布轻量心跳消息到 streetlight/{deviceId}/heartbeat，
     * MqttSubscriber 收到后仅更新 lastHeartbeatAt 和在线状态，不触发决策引擎。
     */
    @Scheduled(initialDelay = 10000, fixedRate = 30000)
    public void generateHeartbeats() {
        LambdaQueryWrapper<Device> query = new LambdaQueryWrapper<Device>()
                .eq(Device::getEnabled, true)
                .eq(Device::getDeleted, false);
        List<Device> devices = deviceMapper.selectList(query);
        if (devices.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        for (Device device : devices) {
            try {
                Map<String, Object> hb = new HashMap<>();
                hb.put("deviceId", device.getDeviceId());
                hb.put("timestamp", now.toString());
                String json = objectMapper.writeValueAsString(hb);
                String topic = mqttProperties.getTopicPrefix() + "/" + device.getDeviceId() + "/heartbeat";
                mqttPublisher.publish(topic, json, 1);
                count++;
            } catch (Exception e) {
                log.error("心跳发布失败 [{}]: {}", device.getDeviceId(), e.getMessage());
            }
        }
    }

    /** 模拟设备回复 ACK：查询最近 10 分钟内无确认的指令，以 90% 概率确认。 */
    private void simulateAcks(List<Device> devices) {
        int ackCount = 0;
        for (Device device : devices) {
            List<ControlCommand> pending = controlCommandMapper.selectList(
                    new LambdaQueryWrapper<ControlCommand>()
                            .eq(ControlCommand::getDeviceId, device.getDeviceId())
                            .isNull(ControlCommand::getAckAt)
                            .ge(ControlCommand::getIssuedAt, LocalDateTime.now().minusMinutes(10)));
            for (ControlCommand cmd : pending) {
                if (random.nextDouble() < 0.9) {
                    try {
                        Map<String, Object> ack = new HashMap<>();
                        ack.put("commandId", cmd.getId());
                        ack.put("status", "EXECUTED");
                        ack.put("completedAt", LocalDateTime.now().toString());
                        String json = objectMapper.writeValueAsString(ack);
                        String topic = mqttProperties.getTopicPrefix() + "/" + device.getDeviceId() + "/command/ack";
                        mqttPublisher.publish(topic, json, 0);
                        ackCount++;
                    } catch (Exception e) {
                        log.error("ACK发布失败 [{}]: {}", device.getDeviceId(), e.getMessage());
                    }
                }
            }
        }
    }

    private void publishVisionEvent(String deviceId, String eventType, double confidence) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("deviceId", deviceId);
            event.put("eventType", eventType);
            event.put("confidence", BigDecimal.valueOf(confidence).setScale(2, RoundingMode.HALF_UP));
            event.put("snapshotRef", "mock/snapshot/" + deviceId + "_" + System.currentTimeMillis() + ".jpg");
            event.put("occurredAt", LocalDateTime.now().toString());
            String json = objectMapper.writeValueAsString(event);
            String topic = mqttProperties.getTopicPrefix() + "/" + deviceId + "/vision/event";
            mqttPublisher.publish(topic, json, 0);
        } catch (Exception e) {
            log.error("视觉事件发布失败 [{}]: {}", deviceId, e.getMessage());
        }
    }

    private static final String[] VOICE_CONTENTS = {
        "请注意，前方路段照明已开启，行人请注意安全",
        "当前区域光照不足，路灯已自动调亮至80%",
        "雨雾天气预警，请减速慢行，开启雾灯",
        "设备自检完成，所有模块运行正常",
        "夜间节能模式已启动，路灯亮度降至30%",
        "道路施工区域，请注意避让",
        "该区域车流量较大，已切换为高峰亮灯模式",
        "空气质量异常，建议减少户外活动",
    };

    private void publishVoiceEvent(String deviceId) {
        try {
            String content = VOICE_CONTENTS[random.nextInt(VOICE_CONTENTS.length)];
            String type = content.contains("预警") || content.contains("异常") ? "警告" :
                    content.contains("请") ? "播报" : "广播";
            Map<String, Object> event = new HashMap<>();
            event.put("deviceId", deviceId);
            event.put("type", type);
            event.put("content", content);
            event.put("source", "自动");
            event.put("occurredAt", LocalDateTime.now().toString());
            String json = objectMapper.writeValueAsString(event);
            String topic = mqttProperties.getTopicPrefix() + "/" + deviceId + "/voice/event";
            mqttPublisher.publish(topic, json, 0);
        } catch (Exception e) {
            log.error("语音事件发布失败 [{}]: {}", deviceId, e.getMessage());
        }
    }
}
