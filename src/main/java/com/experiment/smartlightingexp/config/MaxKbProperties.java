package com.experiment.smartlightingexp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "maxkb")
public class MaxKbProperties {

    private String chatCompletionsUrl;

    private String apiKey;

    private String model = "gpt-3.5-turbo";

    public boolean isConfigured() {
        return hasText(chatCompletionsUrl) && hasText(apiKey);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
