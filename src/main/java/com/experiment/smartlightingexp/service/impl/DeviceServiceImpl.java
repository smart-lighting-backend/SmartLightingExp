package com.experiment.smartlightingexp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.experiment.smartlightingexp.dto.DevicePageRequest;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.service.DeviceService;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;

/**
 * 设备 Service 实现 — 设备台账的业务逻辑。
 */
@Service
public class DeviceServiceImpl extends ServiceImpl<DeviceMapper, Device> implements DeviceService {

    private static final long MAX_PAGE_SIZE = 100L;

    @Override
    public Page<Device> pageDevices(DevicePageRequest request) {
        long pageNum = normalizePageNum(request.getPageNum());
        long pageSize = normalizePageSize(request.getPageSize());

        LambdaQueryWrapper<Device> wrapper = new LambdaQueryWrapper<Device>()
                .eq(Device::getDeleted, false);

        if (StringUtils.hasText(request.getKeyword())) {
            String keyword = request.getKeyword().trim();
            wrapper.and(query -> query
                    .like(Device::getDeviceId, keyword)
                    .or()
                    .like(Device::getName, keyword)
                    .or()
                    .like(Device::getLocation, keyword));
        }
        if (StringUtils.hasText(request.getArea())) {
            wrapper.eq(Device::getArea, request.getArea().trim());
        }
        if (request.getStatus() != null) {
            wrapper.eq(Device::getStatus, request.getStatus());
        }
        if (request.getEnabled() != null) {
            wrapper.eq(Device::getEnabled, request.getEnabled());
        }

        wrapper.orderByAsc(Device::getArea).orderByAsc(Device::getDeviceId);
        return page(new Page<>(pageNum, pageSize), wrapper);
    }

    private long normalizePageNum(Long pageNum) {
        if (pageNum == null || pageNum < 1) {
            return 1L;
        }
        return pageNum;
    }

    private long normalizePageSize(Long pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 10L;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
