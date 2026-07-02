package com.experiment.smartlightingexp.config;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@EnableConfigurationProperties(MqttProperties.class)
public class MqttConfig {

    @Bean(destroyMethod = "disconnect")
    public MqttClient mqttClient(MqttProperties props) throws MqttException {
        MqttClient client = new MqttClient(props.getBroker(), props.getClientId(), new MemoryPersistence());

        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(30);
        options.setKeepAliveInterval(60);
        options.setUserName(props.getUsername());
        options.setPassword(props.getPassword().toCharArray());

        client.connect(options);
        log.info("MQTT connected to {}", props.getBroker());
        return client;
    }
}
