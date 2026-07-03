package com.experiment.smartlightingexp.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 光照阈值配置请求 DTO。
 */
@Data
public class LuxThresholdRequest {

    @NotNull(message = "开灯光照阈值不能为空")
    @DecimalMin(value = "0.00", message = "开灯光照阈值不能小于0")
    private BigDecimal luxLt;

    @NotNull(message = "关灯光照阈值不能为空")
    @DecimalMin(value = "0.00", message = "关灯光照阈值不能小于0")
    private BigDecimal luxGt;
}
