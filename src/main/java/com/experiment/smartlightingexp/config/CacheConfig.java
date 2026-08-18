package com.experiment.smartlightingexp.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地缓存配置 — Caffeine JVM 内缓存，用于热点数据降 DB 压力。
 */
@Configuration
public class CacheConfig {

    /** 角色权限缓存 — key=roleCode, value=权限列表 */
    @Bean
    public Cache<String, List<String>> permissionCache() {
        return Caffeine.newBuilder()
                .maximumSize(200)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }

    /** 地图设备位置缓存 — key="all"/areaName, value=设备位置列表(JSON) */
    @Bean
    public Cache<String, String> mapLocationsCache() {
        return Caffeine.newBuilder()
                .maximumSize(50)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .build();
    }
}
