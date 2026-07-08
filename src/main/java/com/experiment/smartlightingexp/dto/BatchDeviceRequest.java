package com.experiment.smartlightingexp.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量设备操作请求体。
 */
@Data
public class BatchDeviceRequest {

    /** 要操作的设备数据库 ID 列表 */
    @NotEmpty(message = "设备ID列表不能为空")
    private List<Long> deviceIds;
}
