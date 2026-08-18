package com.experiment.smartlightingexp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeviceWifiConfigRequest {

    @NotBlank(message = "WiFi名称不能为空")
    private String wifiSsid;

    @NotBlank(message = "WiFi密码不能为空")
    private String wifiPassword;
}
