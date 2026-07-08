package com.experiment.smartlightingexp.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class TdengineConfig {

    @Bean
    public JdbcTemplate tdengineJdbcTemplate(TdengineProperties props) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(props.getUrl());
        config.setUsername(props.getUsername());
        config.setPassword(props.getPassword());
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10000);
        config.setInitializationFailTimeout(-1);
        HikariDataSource ds = new HikariDataSource(config);
        return new JdbcTemplate(ds);
    }
}
