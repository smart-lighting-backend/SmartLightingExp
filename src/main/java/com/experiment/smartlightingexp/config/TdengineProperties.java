package com.experiment.smartlightingexp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "tdengine")
public class TdengineProperties {

    private String url;

    private String username;

    private String password;
}
