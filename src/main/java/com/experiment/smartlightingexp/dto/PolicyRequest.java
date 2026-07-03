package com.experiment.smartlightingexp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 照明策略请求 DTO — 创建/更新策略时使用。
 */
@Data
public class PolicyRequest {

    @NotBlank(message = "策略名称不能为空")
    private String name;

    @NotBlank(message = "策略类型不能为空")
    private String policyType;

    @NotBlank(message = "条件配置不能为空")
    private String conditions;

    @NotBlank(message = "执行动作不能为空")
    private String action;

    private Integer priority;

    private Boolean enabled;

    private String effectiveTime;
}
