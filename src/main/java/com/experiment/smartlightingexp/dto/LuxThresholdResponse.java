package com.experiment.smartlightingexp.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 光照阈值配置响应 DTO。
 */
@Data
public class LuxThresholdResponse {

    private Long policyId;

    private String policyName;

    private BigDecimal luxLt;

    private BigDecimal luxGt;

    private String conditions;

    private Boolean enabled;

    private Integer priority;
}
