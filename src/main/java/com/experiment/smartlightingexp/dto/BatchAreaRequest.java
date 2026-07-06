package com.experiment.smartlightingexp.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量设备分区分配请求体。
 */
@Data
public class BatchAreaRequest {

    /** 要分配的设备 ID 列表 */
    @NotEmpty(message = "设备ID列表不能为空")
    private List<Long> deviceIds;

    /** 目标区域 ID（传 null 则清除区域） */
    private Long areaId;
}
