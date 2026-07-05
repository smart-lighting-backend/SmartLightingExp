package com.experiment.smartlightingexp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.experiment.smartlightingexp.common.BusinessException;
import com.experiment.smartlightingexp.dto.DeviceCreateRequest;
import com.experiment.smartlightingexp.dto.DevicePageRequest;
import com.experiment.smartlightingexp.dto.DeviceUpdateRequest;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.service.DeviceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/**
 * Device service implementation for device ledger management.
 */
@Service
public class DeviceServiceImpl extends ServiceImpl<DeviceMapper, Device> implements DeviceService {

    private static final long MAX_PAGE_SIZE = 100L;
    private static final int DEVICE_STATUS_DISABLED = 0;
    private static final int DEVICE_STATUS_ONLINE = 1;
    private static final String DEFAULT_TOPIC_PREFIX = "streetlight";
    private static final BigDecimal DEFAULT_HEALTH_SCORE = new BigDecimal("100.00");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Device createDevice(DeviceCreateRequest request) {
        String deviceId = normalizeText(request.getDeviceId());
        if (!StringUtils.hasText(deviceId)) {
            throw new BusinessException(400, "设备编号不能为空");
        }
        long duplicateCount = count(new LambdaQueryWrapper<Device>()
                .eq(Device::getDeviceId, deviceId));
        if (duplicateCount > 0) {
            throw new BusinessException(409, "设备编号已存在");
        }

        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setName(normalizeText(request.getName()));
        device.setArea(normalizeText(request.getArea()));
        device.setLocation(normalizeText(request.getLocation()));
        device.setStatus(request.getStatus() == null ? DEVICE_STATUS_ONLINE : request.getStatus());
        device.setHealthScore(request.getHealthScore() == null ? DEFAULT_HEALTH_SCORE : request.getHealthScore());
        device.setTopicPrefix(StringUtils.hasText(request.getTopicPrefix())
                ? normalizeText(request.getTopicPrefix())
                : DEFAULT_TOPIC_PREFIX);
        device.setEnabled(request.getEnabled() == null || request.getEnabled());
        device.setDeleted(false);
        save(device);
        return device;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Device updateDevice(String deviceId, DeviceUpdateRequest request) {
        Device existing = getActiveDevice(deviceId);

        Device update = new Device();
        update.setId(existing.getId());
        if (request.getName() != null) {
            update.setName(normalizeText(request.getName()));
        }
        if (request.getArea() != null) {
            update.setArea(normalizeText(request.getArea()));
        }
        if (request.getLocation() != null) {
            update.setLocation(normalizeText(request.getLocation()));
        }
        if (request.getStatus() != null) {
            update.setStatus(request.getStatus());
        }
        if (request.getHealthScore() != null) {
            update.setHealthScore(request.getHealthScore());
        }
        if (request.getTopicPrefix() != null) {
            update.setTopicPrefix(StringUtils.hasText(request.getTopicPrefix())
                    ? normalizeText(request.getTopicPrefix())
                    : DEFAULT_TOPIC_PREFIX);
        }
        if (request.getEnabled() != null) {
            update.setEnabled(request.getEnabled());
        }

        updateById(update);
        return getActiveDevice(existing.getDeviceId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDevice(String deviceId) {
        Device existing = getActiveDevice(deviceId);
        update(new LambdaUpdateWrapper<Device>()
                .eq(Device::getId, existing.getId())
                .set(Device::getEnabled, false)
                .set(Device::getStatus, DEVICE_STATUS_DISABLED));
        removeById(existing.getId());
    }

    @Override
    public Device getDeviceDetail(String deviceId) {
        return getActiveDevice(deviceId);
    }

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

    private Device getActiveDevice(String deviceId) {
        String normalizedDeviceId = normalizeText(deviceId);
        if (!StringUtils.hasText(normalizedDeviceId)) {
            throw new BusinessException(400, "设备编号不能为空");
        }
        Device device = getOne(new LambdaQueryWrapper<Device>()
                .eq(Device::getDeviceId, normalizedDeviceId)
                .eq(Device::getDeleted, false));
        if (device == null) {
            throw new BusinessException(404, "设备不存在");
        }
        return device;
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

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
