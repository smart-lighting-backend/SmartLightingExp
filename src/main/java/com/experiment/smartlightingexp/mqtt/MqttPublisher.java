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

@Slf4j
@Component
public class MqttPublisher {

    private final MqttClient mqttClient;
    private final ExecutorService publishExecutor = Executors.newSingleThreadExecutor();

    public MqttPublisher(MqttClient mqttClient) {
        this.mqttClient = mqttClient;
    }

    public void publish(String topic, String payload, int qos) {
        Future<?> future = publishExecutor.submit(() -> {
            try {
                MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
                message.setQos(qos);
                message.setRetained(false);
                mqttClient.publish(topic, message);
            } catch (MqttException e) {
                log.error("MQTT publish failed to topic {}: {}", topic, e.getMessage());
            }
        });
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("MQTT publish timeout (5s), topic={}", topic);
            future.cancel(true);
        }
    }

    public boolean isConnected() {
        return mqttClient != null && mqttClient.isConnected();
    }
}
