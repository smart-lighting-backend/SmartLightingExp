package com.experiment.smartlightingexp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 设备查询请求 DTO — 支持多条件组合筛选 + 分页。
 */
@Data
public class DeviceQueryRequest {

    /** 设备编号（精确匹配） */
    private String deviceId;

    /** 设备名称（模糊匹配） */
    private String name;

    /** 区域（精确匹配文本） */
    private String area;

    /** 关联 device_area.id */
    private Long areaId;

    /** 安装位置（模糊匹配） */
    private String location;

    /** 状态：0-停用，1-在线，2-离线，3-异常 */
    private Integer status;

    /** 是否启用 */
    private Boolean enabled;

    /** 健康评分最小值 */
    private BigDecimal healthScoreMin;

    /** 健康评分最大值 */
    private BigDecimal healthScoreMax;

    // ======================== 分页参数 ========================

    /** 当前页码，默认 1 */
    @JsonProperty("pageNum")
    private int page = 1;

    /** 每页条数，默认 20 */
    @JsonProperty("pageSize")
    private int size = 20;
}
