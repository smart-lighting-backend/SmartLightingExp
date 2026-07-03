package com.experiment.smartlightingexp.dto;

import lombok.Data;

@Data
public class DevicePageRequest {

    private Long pageNum = 1L;

    private Long pageSize = 10L;

    private String keyword;

    private String area;

    private Integer status;

    private Boolean enabled;
}
