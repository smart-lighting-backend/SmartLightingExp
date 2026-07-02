package com.experiment.smartlightingexp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.service.DeviceService;
import org.springframework.stereotype.Service;

/**
 * 设备 Service 实现 — 设备台账的业务逻辑。
 */
@Service
public class DeviceServiceImpl extends ServiceImpl<DeviceMapper, Device> implements DeviceService {
}
