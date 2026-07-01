package com.experiment.smartlightingexp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置属性 — 绑定 application.yaml 中 jwt.* 的配置项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /**
     * 签名密钥（Base64 编码，至少 256 位）
     */
    private String secret;

    /**
     * Token 过期时间（毫秒），默认 24 小时
     */
    private long expiration;
}
