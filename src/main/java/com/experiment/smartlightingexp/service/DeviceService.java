package com.experiment.smartlightingexp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.experiment.smartlightingexp.dto.DevicePageRequest;
import com.experiment.smartlightingexp.entity.Device;

/**
 * 设备 Service — 设备台账的业务逻辑接口。
 */
public interface DeviceService extends IService<Device> {

    Page<Device> pageDevices(DevicePageRequest request);
}
