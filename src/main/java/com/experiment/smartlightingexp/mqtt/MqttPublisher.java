package com.experiment.smartlightingexp.mqtt;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class MqttPublisher {

    private final MqttClient mqttClient;
    private final ExecutorService publishExecutor = Executors.newSingleThreadExecutor();

    public MqttPublisher(MqttClient mqttClient) {
        this.mqttClient = mqttClient;
    }

    public void publish(String topic, String payload, int qos) {
        // 发布前检查连接状态
        if (!mqttClient.isConnected()) {
            String err = String.format("MQTT client not connected, cannot publish to %s", topic);
            log.error(err);
            throw new RuntimeException(err);
        }

        AtomicReference<MqttException> publishError = new AtomicReference<>();
        Future<?> future = publishExecutor.submit(() -> {
            try {
                MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
                message.setQos(qos);
                message.setRetained(false);
                mqttClient.publish(topic, message);
            } catch (MqttException e) {
                log.error("MQTT publish failed to topic {}: {}", topic, e.getMessage());
                publishError.set(e);
            }
        });
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("MQTT publish timeout (10s), topic={}", topic);
            future.cancel(true);
            throw new RuntimeException("MQTT publish timeout: " + topic);
        } catch (Exception e) {
            log.error("MQTT publish interrupted, topic={}: {}", topic, e.getMessage());
            future.cancel(true);
            throw new RuntimeException("MQTT publish interrupted: " + topic, e);
        }

        if (publishError.get() != null) {
            throw new RuntimeException("MQTT publish failed: " + publishError.get().getMessage(), publishError.get());
        }
    }

    public boolean isConnected() {
        return mqttClient != null && mqttClient.isConnected();
    }
}
