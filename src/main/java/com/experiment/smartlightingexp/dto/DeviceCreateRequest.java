package com.experiment.smartlightingexp.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class DeviceCreateRequest {

    @NotBlank(message = "设备编号不能为空")
    private String deviceId;

    private String name;

    private String area;

    private String location;

    @Min(value = 0, message = "设备状态不能小于0")
    @Max(value = 3, message = "设备状态不能大于3")
    private Integer status;

    @DecimalMin(value = "0.00", message = "健康评分不能小于0")
    @DecimalMax(value = "100.00", message = "健康评分不能大于100")
    private BigDecimal healthScore;

    private String topicPrefix;

    private Boolean enabled;
}
