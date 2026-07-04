package com.experiment.smartlightingexp.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.experiment.smartlightingexp.config.MqttProperties;
import com.experiment.smartlightingexp.entity.Device;
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
        log.info("===== Mock Data Generation =====");
        log.info("Enabled devices found: {}", devices.size());
        if (devices.isEmpty()) {
            log.warn("No devices available, skip generation");
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
                mqttPublisher.publish(topic, json, 1);
                log.info("  [{}] → illuminance={}, temp={}°C, humidity={}%, pm25={}, aqi={}, pir={}, traffic={}",
                        device.getDeviceId(), illuminance,
                        String.format("%.1f", temperature), humidity, pm25, aqi, pir, trafficFlow);
                successCount++;
            } catch (Exception e) {
                log.error("  [{}] ✗ publish failed: {}", device.getDeviceId(), e.getMessage());
            }
        }
        log.info("Result: {}/{} published", successCount, devices.size());
        log.info("================================");
    }
}
