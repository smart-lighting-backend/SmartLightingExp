package com.experiment.smartlightingexp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 遥测数据查询请求 DTO — 支持按设备、时间范围组合筛选 + 分页。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TelemetryQueryRequest {

    /** 设备ID（精确匹配） */
    private String deviceId;

    /** 采集时间范围（起始） */
    private LocalDateTime collectedAtFrom;

    /** 采集时间范围（截止） */
    private LocalDateTime collectedAtTo;

    // ======================== 分页参数 ========================

    /** 当前页码，默认 1 */
    private int page = 1;

    /** 每页条数，默认 20 */
    private int size = 20;
}
