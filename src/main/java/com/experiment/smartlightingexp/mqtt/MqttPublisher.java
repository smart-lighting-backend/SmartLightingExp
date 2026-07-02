package com.experiment.smartlightingexp.mqtt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class MqttPublisher {

    private final MqttClient mqttClient;

    public void publish(String topic, String payload, int qos) {
        try {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(qos);
            message.setRetained(false);
            mqttClient.publish(topic, message);
            log.debug("MQTT published to {}: {}", topic, payload);
        } catch (MqttException e) {
            log.error("MQTT publish failed to topic {}: {}", topic, e.getMessage());
        }
    }
}
