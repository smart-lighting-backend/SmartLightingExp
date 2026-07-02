package com.experiment.smartlightingexp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "mqtt")
public class MqttProperties {

    private String broker;
    private String username;
    private String password;
    private String clientId;
    private String topicPrefix;
}
