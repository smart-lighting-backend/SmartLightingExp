package com.experiment.smartlightingexp.dto;

import lombok.Data;

/**
 * 策略查询请求 DTO — 支持多条件混合筛选 + 分页。
 */
@Data
public class PolicyQueryRequest {

    /** 策略名称（LIKE 模糊） */
    private String name;

    /** 策略类型（精确） */
    private String policyType;

    /** 启用状态（精确） */
    private Boolean enabled;

    /** 优先级最小值 */
    private Integer priorityMin;

    /** 优先级最大值 */
    private Integer priorityMax;

    /** 生效时段（LIKE 模糊） */
    private String effectiveTime;

    // ======================== 分页参数 ========================

    /** 页码，默认 1 */
    private int page = 1;

    /** 每页条数，默认 20 */
    private int size = 20;
}
