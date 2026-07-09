package com.experiment.smartlightingexp.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警查询请求 DTO — 支持按设备、类型、级别、状态、时间范围和处理人组合筛选。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AlarmQueryRequest {

    /** 设备ID（模糊匹配） */
    private String deviceId;

    /** 告警类型：OFFLINE / FAULT / HEALTH_LOW */
    private String type;

    /** 告警级别：CRITICAL / MAJOR / WARNING */
    private String level;

    /** 告警状态：ACTIVE / RECOVERED / ACKNOWLEDGED */
    private String status;

    /** 告警开始时间范围（起始） */
    private LocalDateTime startAtFrom;

    /** 告警开始时间范围（截止） */
    private LocalDateTime startAtTo;

    /** 处理人（模糊匹配） */
    private String handler;

    // ======================== 分页参数 ========================

    /** 当前页码，默认 1 */
    @JsonProperty("pageNum")
    private int page = 1;

    /** 每页条数，默认 20（兼容 size / pageSize） */
    @JsonProperty("pageSize")
    @JsonAlias({"size"})
    private int size = 20;
}
