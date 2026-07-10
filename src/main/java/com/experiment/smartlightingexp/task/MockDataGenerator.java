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

    /** 心跳跳过列表：deviceId → 跳过截止时间（用于模拟离线） */
    private final java.util.concurrent.ConcurrentHashMap<String, java.time.LocalDateTime> skipHeartbeatUntil = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 让指定设备在 {@code seconds} 秒内不发送心跳（模拟离线）。
     * 由 DeviceController.offlineSimulate 调用。
     */
    public void skipHeartbeat(String deviceId, int seconds) {
        skipHeartbeatUntil.put(deviceId, java.time.LocalDateTime.now().plusSeconds(seconds));
        log.info("[MockGen] {} 离线模拟: 跳过心跳 {} 秒", deviceId, seconds);
    }

    /**
     * 模拟数据生成 — 分批发布，每批 20 台设备，批次间隔 500ms。
     */
    private static final int BATCH_SIZE = 20;
    private static final long BATCH_DELAY_MS = 500;
    private static final long DEVICE_DELAY_MS = 0;

    @Scheduled(initialDelay = 30000, fixedRate = 60000)
    public void generateData() {
        log.info("MockGen: generateData() 被调度触发");
        if (!mqttPublisher.isConnected()) {
            log.warn("MockGen: MQTT 未连接，跳过本轮遥测生成");
            return;
        }
        log.info("MockGen: MQTT 已连接，开始查询设备...");
        List<Device> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<Device>()
                        .eq(Device::getEnabled, true)
                        .eq(Device::getDeleted, false));
        if (devices.isEmpty()) {
            log.warn("MockGen: 未找到已启用设备，跳过遥测生成");
            return;
        }
        log.info("MockGen: 查到 {} 台设备，开始发布...", devices.size());

        int successCount = 0;
        int batchIndex = 0;
        for (int i = 0; i < devices.size(); i++) {
            Device device = devices.get(i);
            log.info("  [{}/{}] 发布遥测: {}", i + 1, devices.size(), device.getDeviceId());

            publishTelemetry(device);
            successCount++;
            log.info("  [{}/{}] 遥测完成", i + 1, devices.size());

            // 视觉事件
            int pir = random.nextInt(2);
            int trafficFlow = random.nextInt(50);
            if (pir == 1 && random.nextDouble() < 0.5) {
                publishVisionEvent(device.getDeviceId(), "行人检测", 0.70 + 0.29 * random.nextDouble());
            }
            if (trafficFlow > 10 && random.nextDouble() < 0.5) {
                publishVisionEvent(device.getDeviceId(), "车辆通行", 0.70 + 0.29 * random.nextDouble());
            }
            if (random.nextDouble() < 0.08) {
                publishVisionEvent(device.getDeviceId(), random.nextBoolean() ? "异常停车" : "危险场景", 0.60 + 0.39 * random.nextDouble());
            }
            if (random.nextDouble() < 0.2) {
                publishVoiceEvent(device.getDeviceId());
            }

            // 每批结束等待
            if ((i + 1) % BATCH_SIZE == 0 && i < devices.size() - 1) {
                batchIndex++;
                log.info("  --- 第{}批完成 ({}/{}), 等待{}ms ---", batchIndex, i + 1, devices.size(), BATCH_DELAY_MS);
                try { Thread.sleep(BATCH_DELAY_MS); } catch (InterruptedException ignored) {}
            } else {
                try { Thread.sleep(DEVICE_DELAY_MS); } catch (InterruptedException ignored) {}
            }
        }
        log.info("Mock遥测完成: {}/{} 台设备 (共{}批)", successCount, devices.size(), batchIndex + 1);

        simulateAcks(devices);
    }

    private void publishTelemetry(Device device) {
        Map<String, Object> data = new HashMap<>();
        data.put("deviceId", device.getDeviceId());
        data.put("illuminance", BigDecimal.valueOf(random.nextInt(2000)));
        data.put("temperature", BigDecimal.valueOf(15 + (40 - 15) * random.nextDouble()).setScale(2, RoundingMode.HALF_UP));
        data.put("humidity", BigDecimal.valueOf(40 + random.nextInt(50)));
        data.put("pm25", BigDecimal.valueOf(10 + random.nextInt(140)));
        data.put("aqi", random.nextInt(200));
        data.put("pir", random.nextInt(2));
        data.put("trafficFlow", random.nextInt(50));
        data.put("collectedAt", LocalDateTime.now().toString());
        try {
            mqttPublisher.publish(
                    mqttProperties.getTopicPrefix() + "/" + device.getDeviceId() + "/telemetry",
                    objectMapper.writeValueAsString(data), 0);
        } catch (Exception e) {
            log.error("遥测发布失败 [{}]: {}", device.getDeviceId(), e.getMessage());
        }
    }

    /**
     * 心跳模拟 — 启动 10s 后首次执行，之后每 30 秒一次。
     * 为每台已启用设备发布轻量心跳消息到 streetlight/{deviceId}/heartbeat，
     * MqttSubscriber 收到后仅更新 lastHeartbeatAt 和在线状态，不触发决策引擎。
     */
    @Scheduled(initialDelay = 10000, fixedRate = 30000)
    public void generateHeartbeats() {
        if (!mqttPublisher.isConnected()) {
            log.warn("MockGen: MQTT 未连接，跳过本轮心跳生成");
            return;
        }
        List<Device> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<Device>()
                        .eq(Device::getEnabled, true)
                        .eq(Device::getDeleted, false));
        if (devices.isEmpty()) {
            log.warn("MockGen: 未找到已启用设备，跳过心跳生成");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        int skipped = 0;
        for (int i = 0; i < devices.size(); i++) {
            Device device = devices.get(i);
            LocalDateTime skipUntil = skipHeartbeatUntil.get(device.getDeviceId());
            if (skipUntil != null) {
                if (now.isBefore(skipUntil)) {
                    skipped++;
                    continue;
                } else {
                    skipHeartbeatUntil.remove(device.getDeviceId());
                }
            }
            try {
                Map<String, Object> hb = new HashMap<>();
                hb.put("deviceId", device.getDeviceId());
                hb.put("timestamp", now.toString());
                mqttPublisher.publish(
                        mqttProperties.getTopicPrefix() + "/" + device.getDeviceId() + "/heartbeat",
                        objectMapper.writeValueAsString(hb), 0);
                count++;
            } catch (Exception e) {
                log.error("心跳发布失败 [{}]: {}", device.getDeviceId(), e.getMessage());
            }
            if ((i + 1) % BATCH_SIZE == 0 && i < devices.size() - 1) {
                try { Thread.sleep(BATCH_DELAY_MS); } catch (InterruptedException ignored) {}
            } else {
                try { Thread.sleep(DEVICE_DELAY_MS); } catch (InterruptedException ignored) {}
            }
        }
        if (count > 0 || skipped > 0)
            log.info("Mock心跳: {}/{} 台设备已发布 ({}台跳过)", count, devices.size(), skipped);
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
