package com.experiment.smartlightingexp.dto;

import lombok.Data;

@Data
public class DevicePageRequest {

    private Long pageNum = 1L;

    private Long pageSize = 10L;

    private String keyword;

    /** 区域名称（文本匹配） */
    private String area;

    /** 关联 device_area.id */
    private Long areaId;

    private Integer status;

    private Boolean enabled;
}
