package com.experiment.smartlightingexp.config;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

@Slf4j
@Configuration
@EnableConfigurationProperties(MqttProperties.class)
public class MqttConfig {

    @Bean(destroyMethod = "disconnect")
    public MqttClient mqttClient(MqttProperties props) throws MqttException {
        String uniqueClientId = props.getClientId() + "-" + System.currentTimeMillis();
        log.info("MQTT clientId={}", uniqueClientId);
        MqttClient client = new MqttClient(props.getBroker(), uniqueClientId, new MemoryPersistence());

        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setExecutorServiceTimeout(10);
        options.setKeepAliveInterval(15);
        options.setMaxInflight(50);
        options.setUserName(props.getUsername());
        options.setPassword(props.getPassword().toCharArray());
        options.setSocketFactory(createTrustAllSocketFactory());
        options.setHttpsHostnameVerificationEnabled(false);

        client.connect(options);
        log.info("MQTT connected to {}", props.getBroker());
        return client;
    }

    private static SSLSocketFactory createTrustAllSocketFactory() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] c, String a) {}
                        public void checkServerTrusted(X509Certificate[] c, String a) {}
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, trustAll, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("SSL SocketFactory 创建失败", e);
        }
    }
}
