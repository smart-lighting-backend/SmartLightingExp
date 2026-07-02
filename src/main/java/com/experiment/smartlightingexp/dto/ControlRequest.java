package com.experiment.smartlightingexp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 手动控制请求参数。
 */
@Data
public class ControlRequest {

    @NotBlank(message = "指令类型不能为空")
    private String action;

    @Min(value = 0, message = "亮度不能低于 0")
    @Max(value = 100, message = "亮度不能高于 100")
    private Integer brightness;
}
