package com.experiment.smartlightingexp.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.experiment.smartlightingexp.config.MqttProperties;
import com.experiment.smartlightingexp.entity.ControlCommand;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.mapper.ControlCommandMapper;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.service.DeviceCredentialService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 模拟设备数据生成器。
 * 每台设备使用独立的 MQTT SSL 连接，用自己的 username/password 鉴权发布数据，
 * 完整走 EMQX MySQL 认证 + ACL 隔离流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mock.enabled", havingValue = "true")
public class MockDataGenerator {

    private final DeviceMapper deviceMapper;
    private final ObjectMapper objectMapper;
    private final MqttProperties mqttProperties;
    private final ControlCommandMapper controlCommandMapper;
    private final DeviceCredentialService credentialService;

    private final Random random = new Random();

    /** 心跳跳过列表：deviceId → 跳过截止时间（用于模拟离线） */
    private final java.util.concurrent.ConcurrentHashMap<String, LocalDateTime> skipHeartbeatUntil =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** EMQX SSL 端口 */
    private static final String BROKER_TEMPLATE = "ssl://%s:8883";

    /**
     * 让指定设备在 {@code seconds} 秒内不发送心跳（模拟离线）。
     */
    public void skipHeartbeat(String deviceId, int seconds) {
        skipHeartbeatUntil.put(deviceId, LocalDateTime.now().plusSeconds(seconds));
        log.info("[MockGen] {} 离线模拟: 跳过心跳 {} 秒", deviceId, seconds);
    }

    private static final int BATCH_SIZE = 20;
    private static final long BATCH_DELAY_MS = 500;

    /**
     * 模拟数据生成 — 每设备独立 SSL 连接，每批 20 台设备，批次间隔 500ms。
     */
    @Scheduled(initialDelay = 30000, fixedRate = 60000)
    public void generateData() {
        log.info("[MockGen] 开始遥测生成...");
        List<Device> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<Device>()
                        .eq(Device::getEnabled, true)
                        .eq(Device::getDeleted, false));
        if (devices.isEmpty()) {
            log.warn("[MockGen] 未找到已启用设备，跳过");
            return;
        }
        log.info("[MockGen] 查到 {} 台设备，开始逐设备发布", devices.size());

        int successCount = 0;
        int skipCount = 0;
        for (int i = 0; i < devices.size(); i++) {
            Device device = devices.get(i);

            MqttClient client = null;
            try {
                String password = credentialService.getPlainPassword(device.getDeviceId());
                if (password == null) {
                    skipCount++;
                    continue;
                }
                client = createDeviceClient(device.getDeviceId(), password);

                // 遥测
                publishTelemetry(client, device);

                // 视觉事件
                int pir = random.nextInt(2);
                int trafficFlow = random.nextInt(50);
                if (pir == 1 && random.nextDouble() < 0.5) {
                    publishVision(client, device.getDeviceId(), "行人检测", 0.70 + 0.29 * random.nextDouble());
                }
                if (trafficFlow > 10 && random.nextDouble() < 0.5) {
                    publishVision(client, device.getDeviceId(), "车辆通行", 0.70 + 0.29 * random.nextDouble());
                }
                if (random.nextDouble() < 0.08) {
                    publishVision(client, device.getDeviceId(),
                            random.nextBoolean() ? "异常停车" : "危险场景", 0.60 + 0.39 * random.nextDouble());
                }
                if (random.nextDouble() < 0.2) {
                    publishVoice(client, device.getDeviceId());
                }

                successCount++;
            } catch (Exception e) {
                log.error("[MockGen] 设备 {} 发布失败: {}", device.getDeviceId(), e.getMessage());
            } finally {
                closeClient(client);
            }

            // 批次间隔
            if ((i + 1) % BATCH_SIZE == 0 && i < devices.size() - 1) {
                log.info("[MockGen] 第{}批完成 ({}/{})", (i + 1) / BATCH_SIZE, i + 1, devices.size());
                try { Thread.sleep(BATCH_DELAY_MS); } catch (InterruptedException ignored) {}
            }
        }
        log.info("[MockGen] 遥测完成: {}/{} 台设备 ({}台无凭证跳过)", successCount, devices.size(), skipCount);

        simulateAcks(devices);
    }

    /**
     * 心跳模拟 — 每设备独立 SSL 连接发布心跳。
     */
    @Scheduled(initialDelay = 10000, fixedRate = 30000)
    public void generateHeartbeats() {
        List<Device> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<Device>()
                        .eq(Device::getEnabled, true)
                        .eq(Device::getDeleted, false));
        if (devices.isEmpty()) return;

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

            MqttClient client = null;
            try {
                String password = credentialService.getPlainPassword(device.getDeviceId());
                if (password == null) continue;
                client = createDeviceClient(device.getDeviceId(), password);

                Map<String, Object> hb = new HashMap<>();
                hb.put("deviceId", device.getDeviceId());
                hb.put("timestamp", now.toString());
                MqttMessage msg = new MqttMessage(objectMapper.writeValueAsBytes(hb));
                msg.setQos(0);
                client.publish(mqttProperties.getTopicPrefix() + "/" + device.getDeviceId() + "/heartbeat", msg);
                count++;
            } catch (Exception e) {
                log.error("[MockGen] 心跳失败 [{}]: {}", device.getDeviceId(), e.getMessage());
            } finally {
                closeClient(client);
            }

            if ((i + 1) % BATCH_SIZE == 0 && i < devices.size() - 1) {
                try { Thread.sleep(BATCH_DELAY_MS); } catch (InterruptedException ignored) {}
            }
        }
        if (count > 0 || skipped > 0)
            log.info("[MockGen] 心跳: {}/{} 台 ({}台跳过)", count, devices.size(), skipped);
    }

    /** 模拟设备回复 ACK：每设备独立 SSL 连接发布。 */
    private void simulateAcks(List<Device> devices) {
        int ackCount = 0;
        for (Device device : devices) {
            List<ControlCommand> pending = controlCommandMapper.selectList(
                    new LambdaQueryWrapper<ControlCommand>()
                            .eq(ControlCommand::getDeviceId, device.getDeviceId())
                            .isNull(ControlCommand::getAckAt)
                            .ge(ControlCommand::getIssuedAt, LocalDateTime.now().minusMinutes(10)));
            if (pending.isEmpty()) continue;

            MqttClient client = null;
            try {
                String password = credentialService.getPlainPassword(device.getDeviceId());
                if (password == null) continue;
                client = createDeviceClient(device.getDeviceId(), password);

                for (ControlCommand cmd : pending) {
                    if (random.nextDouble() >= 0.9) continue;
                    Map<String, Object> ack = new HashMap<>();
                    ack.put("commandId", cmd.getId());
                    ack.put("status", "EXECUTED");
                    ack.put("completedAt", LocalDateTime.now().toString());
                    MqttMessage msg = new MqttMessage(objectMapper.writeValueAsBytes(ack));
                    msg.setQos(0);
                    client.publish(mqttProperties.getTopicPrefix() + "/" + device.getDeviceId() + "/command/ack", msg);
                    ackCount++;
                }
            } catch (Exception e) {
                log.error("[MockGen] ACK失败 [{}]: {}", device.getDeviceId(), e.getMessage());
            } finally {
                closeClient(client);
            }
        }
        if (ackCount > 0) log.info("[MockGen] ACK: {} 条", ackCount);
    }

    // ─────────── MQTT 客户端工厂 ───────────

    /**
     * 为指定设备创建临时 SSL MQTT 客户端（使用设备自己的凭据）。
     */
    private MqttClient createDeviceClient(String deviceId, String password) throws MqttException {
        String broker = String.format(BROKER_TEMPLATE, extractHost());
        String clientId = "mock-" + deviceId + "-" + System.currentTimeMillis();
        MqttClient client = new MqttClient(broker, clientId, new MemoryPersistence());

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setUserName(deviceId);
        opts.setPassword(password.toCharArray());
        opts.setCleanSession(true);
        opts.setConnectionTimeout(8);
        opts.setKeepAliveInterval(30);
        opts.setSocketFactory(createTrustAllSocketFactory());
        opts.setHttpsHostnameVerificationEnabled(false);

        client.connect(opts);
        return client;
    }

    private void closeClient(MqttClient client) {
        if (client == null) return;
        try { client.disconnect(); } catch (Exception ignored) {}
        try { client.close(); } catch (Exception ignored) {}
    }

    private String extractHost() {
        String broker = mqttProperties.getBroker();
        int start = broker.indexOf("://") + 3;
        int end = broker.lastIndexOf(':');
        if (end < 0 || end <= start) end = broker.length();
        return broker.substring(start, end);
    }

    private static SSLSocketFactory createTrustAllSocketFactory() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] c, String a) {}
                        public void checkServerTrusted(X509Certificate[] c, String a) {}
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, trustAll, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("SSL 初始化失败", e);
        }
    }

    // ─────────── 发布辅助方法 ───────────

    private void publishTelemetry(MqttClient client, Device device) throws Exception {
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

        MqttMessage msg = new MqttMessage(objectMapper.writeValueAsBytes(data));
        msg.setQos(0);
        client.publish(mqttProperties.getTopicPrefix() + "/" + device.getDeviceId() + "/telemetry", msg);
    }

    private void publishVision(MqttClient client, String deviceId, String eventType, double confidence) throws Exception {
        Map<String, Object> event = new HashMap<>();
        event.put("deviceId", deviceId);
        event.put("eventType", eventType);
        event.put("confidence", BigDecimal.valueOf(confidence).setScale(2, RoundingMode.HALF_UP));
        event.put("snapshotRef", "mock/snapshot/" + deviceId + "_" + System.currentTimeMillis() + ".jpg");
        event.put("occurredAt", LocalDateTime.now().toString());

        MqttMessage msg = new MqttMessage(objectMapper.writeValueAsBytes(event));
        msg.setQos(0);
        client.publish(mqttProperties.getTopicPrefix() + "/" + deviceId + "/vision/event", msg);
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

    private void publishVoice(MqttClient client, String deviceId) throws Exception {
        String content = VOICE_CONTENTS[random.nextInt(VOICE_CONTENTS.length)];
        String type = content.contains("预警") || content.contains("异常") ? "警告" :
                content.contains("请") ? "播报" : "广播";
        Map<String, Object> event = new HashMap<>();
        event.put("deviceId", deviceId);
        event.put("type", type);
        event.put("content", content);
        event.put("source", "自动");
        event.put("occurredAt", LocalDateTime.now().toString());

        MqttMessage msg = new MqttMessage(objectMapper.writeValueAsBytes(event));
        msg.setQos(0);
        client.publish(mqttProperties.getTopicPrefix() + "/" + deviceId + "/voice/event", msg);
    }
}
