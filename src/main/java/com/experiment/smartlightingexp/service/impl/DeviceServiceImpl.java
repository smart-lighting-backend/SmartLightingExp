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
import com.experiment.smartlightingexp.entity.DeviceArea;
import com.experiment.smartlightingexp.mapper.DeviceAreaMapper;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.service.DeviceCredentialService;
import com.experiment.smartlightingexp.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

/**
 * Device service implementation for device ledger management.
 */
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl extends ServiceImpl<DeviceMapper, Device> implements DeviceService {

    private final DeviceAreaMapper deviceAreaMapper;
    private final DeviceCredentialService deviceCredentialService;

    private static final long MAX_PAGE_SIZE = 100L;
    private static final int DEVICE_STATUS_DISABLED = 0;
    private static final int DEVICE_STATUS_ONLINE = 1;
    private static final int DEVICE_STATUS_OFFLINE = 2;
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
        // areaId 优先：设置了 areaId 则自动填充区域名
        if (request.getAreaId() != null) {
            DeviceArea area = deviceAreaMapper.selectById(request.getAreaId());
            if (area != null) {
                device.setAreaId(area.getId());
                device.setArea(area.getName());
            }
        } else if (request.getArea() != null) {
            device.setArea(normalizeText(request.getArea()));
        }
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
        if (request.getAreaId() != null) {
            DeviceArea area = deviceAreaMapper.selectById(request.getAreaId());
            if (area != null) {
                update.setAreaId(area.getId());
                update.setArea(area.getName());
            }
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
        if (request.getAreaId() != null) {
            wrapper.eq(Device::getAreaId, request.getAreaId());
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdateArea(List<Long> deviceIds, Long areaId, String areaName) {
        update(new LambdaUpdateWrapper<Device>()
                .in(Device::getId, deviceIds)
                .eq(Device::getDeleted, false)
                .set(Device::getAreaId, areaId)
                .set(Device::getArea, areaName));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDisableDevices(List<Long> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return 0;
        }
        List<Long> activeIds = getActiveDeviceIds(deviceIds);
        if (activeIds.isEmpty()) {
            return 0;
        }

        update(new LambdaUpdateWrapper<Device>()
                .in(Device::getId, activeIds)
                .eq(Device::getDeleted, false)
                .set(Device::getEnabled, false)
                .set(Device::getStatus, DEVICE_STATUS_DISABLED));
        return activeIds.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchEnableDevices(List<Long> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return 0;
        }
        List<Long> activeIds = getActiveDeviceIds(deviceIds);
        if (activeIds.isEmpty()) {
            return 0;
        }

        update(new LambdaUpdateWrapper<Device>()
                .in(Device::getId, activeIds)
                .eq(Device::getDeleted, false)
                .set(Device::getEnabled, true)
                .set(Device::getStatus, DEVICE_STATUS_OFFLINE));
        return activeIds.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDeleteDevices(List<Long> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return 0;
        }
        List<Long> activeIds = getActiveDeviceIds(deviceIds);
        if (activeIds.isEmpty()) {
            return 0;
        }

        // 删除前取出 deviceId，用于后续清理凭证
        List<String> deletedDeviceIds = list(new LambdaQueryWrapper<Device>()
                .select(Device::getDeviceId)
                .in(Device::getId, activeIds))
                .stream()
                .map(Device::getDeviceId)
                .toList();

        update(new LambdaUpdateWrapper<Device>()
                .in(Device::getId, activeIds)
                .eq(Device::getDeleted, false)
                .set(Device::getEnabled, false)
                .set(Device::getStatus, DEVICE_STATUS_DISABLED));
        removeBatchByIds(activeIds);

        // 同步清理 MQTT 凭证（与单设备删除行为一致）
        for (String did : deletedDeviceIds) {
            deviceCredentialService.deleteByDeviceId(did);
        }
        return activeIds.size();
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

    private List<Long> getActiveDeviceIds(List<Long> deviceIds) {
        return list(new LambdaQueryWrapper<Device>()
                .select(Device::getId)
                .in(Device::getId, deviceIds)
                .eq(Device::getDeleted, false))
                .stream()
                .map(Device::getId)
                .toList();
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
