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
    private final ExecutorService publishExecutor = Executors.newFixedThreadPool(8);

    public MqttPublisher(MqttClient mqttClient) {
        this.mqttClient = mqttClient;
    }

    public void publish(String topic, String payload, int qos) {
        if (!mqttClient.isConnected()) {
            log.warn("MQTT 未连接，跳过发布 topic={}", topic);
            return;
        }

        AtomicReference<MqttException> publishError = new AtomicReference<>();
        Future<?> future = publishExecutor.submit(() -> {
            try {
                MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
                message.setQos(qos);
                message.setRetained(false);
                mqttClient.publish(topic, message);
            } catch (MqttException e) {
                publishError.set(e);
            }
        });
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("MQTT 发布超时 topic={}, 后台继续尝试", topic);
        } catch (Exception e) {
            log.warn("MQTT 发布异常 topic={}: {}", topic, e.getMessage());
        }
        if (publishError.get() != null) {
            log.warn("MQTT 发布失败 topic={}: {}", topic, publishError.get().getMessage());
        }
    }

    public boolean isConnected() {
        return mqttClient != null && mqttClient.isConnected();
    }
}
