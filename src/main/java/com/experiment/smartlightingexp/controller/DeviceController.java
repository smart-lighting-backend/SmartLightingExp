package com.experiment.smartlightingexp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.experiment.smartlightingexp.common.RequirePermission;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.common.SecurityContext;
import com.experiment.smartlightingexp.dto.DeviceQueryRequest;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.entity.AuditLog;
import com.experiment.smartlightingexp.mapper.AuditLogMapper;
import com.experiment.smartlightingexp.service.DeviceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备管理控制器 — 设备增删改查 + 组合条件分页查询。
 * 写操作记录审计日志，满足 IR-11 安全可信控制的可追溯要求。
 */
@Slf4j
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;
    private final AuditLogMapper auditLogMapper;

    /**
     * 组合条件分页查询设备列表。
     */
    @PostMapping("/list")
    public Result<IPage<Device>> list(@RequestBody DeviceQueryRequest request) {
        LambdaQueryWrapper<Device> wrapper = new LambdaQueryWrapper<>();

        // 默认只查未删除的设备
        wrapper.eq(Device::getDeleted, false);

        if (request.getDeviceId() != null && !request.getDeviceId().isBlank()) {
            wrapper.eq(Device::getDeviceId, request.getDeviceId());
        }
        if (request.getName() != null && !request.getName().isBlank()) {
            wrapper.like(Device::getName, request.getName());
        }
        if (request.getArea() != null && !request.getArea().isBlank()) {
            wrapper.eq(Device::getArea, request.getArea());
        }
        if (request.getLocation() != null && !request.getLocation().isBlank()) {
            wrapper.like(Device::getLocation, request.getLocation());
        }
        if (request.getStatus() != null) {
            wrapper.eq(Device::getStatus, request.getStatus());
        }
        if (request.getEnabled() != null) {
            wrapper.eq(Device::getEnabled, request.getEnabled());
        }
        if (request.getHealthScoreMin() != null) {
            wrapper.ge(Device::getHealthScore, request.getHealthScoreMin());
        }
        if (request.getHealthScoreMax() != null) {
            wrapper.le(Device::getHealthScore, request.getHealthScoreMax());
        }

        wrapper.orderByDesc(Device::getCreateTime);

        Page<Device> page = new Page<>(request.getPage(), request.getSize());
        IPage<Device> result = deviceService.page(page, wrapper);

        log.info("[设备查询] 条件: deviceId={}, name={}, area={}, status={}, 结果数={}",
                request.getDeviceId(), request.getName(), request.getArea(),
                request.getStatus(), result.getRecords().size());

        return Result.success(result);
    }

    /**
     * 分页查询设备列表（GET 方式，URL 查询参数）。
     * 支持关键词搜索（匹配 deviceId / name / location）+ 区域 / 状态 / 启用状态筛选。
     */
    @GetMapping("/page")
    public Result<IPage<Device>> page(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Boolean enabled) {

        // 参数校验
        if (pageNum < 1) pageNum = 1;
        if (pageSize < 1) pageSize = 10;
        if (pageSize > 100) pageSize = 100;

        LambdaQueryWrapper<Device> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Device::getDeleted, false);

        // 关键词模糊匹配 deviceId / name / location
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w
                    .like(Device::getDeviceId, keyword)
                    .or().like(Device::getName, keyword)
                    .or().like(Device::getLocation, keyword));
        }
        if (area != null && !area.isBlank()) {
            wrapper.eq(Device::getArea, area);
        }
        if (status != null) {
            wrapper.eq(Device::getStatus, status);
        }
        if (enabled != null) {
            wrapper.eq(Device::getEnabled, enabled);
        }

        // 排序：区域升序 → 设备编号升序
        wrapper.orderByAsc(Device::getArea).orderByAsc(Device::getDeviceId);

        Page<Device> page = new Page<>(pageNum, pageSize);
        IPage<Device> result = deviceService.page(page, wrapper);

        log.info("[设备分页] keyword={}, area={}, status={}, enabled={}, 结果数={}",
                keyword, area, status, enabled, result.getRecords().size());
        return Result.success(result);
    }

    /**
     * 查询单个设备详情。
     */
    @GetMapping("/{deviceId}")
    public Result<Device> getByDeviceId(@PathVariable String deviceId) {
        Device device = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, deviceId)
                .eq(Device::getDeleted, false)
                .one();
        if (device == null) {
            return Result.error("设备不存在");
        }
        return Result.success(device);
    }

    /**
     * 新增设备。
     */
    @PostMapping
    @RequirePermission("device:create")
    public Result<Device> create(@RequestBody Device device,
                                 HttpServletRequest httpRequest) {
        if (device.getDeviceId() == null || device.getDeviceId().isBlank()) {
            return Result.error(400, "设备编号不能为空");
        }

        // 检查 deviceId 唯一性（包含已删除的数据，因为唯一索引仍占用）
        Device exist = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, device.getDeviceId())
                .one();
        if (exist != null) {
            return Result.error(409, "设备编号已存在");
        }

        if (device.getStatus() == null) device.setStatus(1);
        if (device.getEnabled() == null) device.setEnabled(true);
        if (device.getHealthScore() == null) device.setHealthScore(new BigDecimal("100.00"));
        if (device.getTopicPrefix() == null || device.getTopicPrefix().isBlank()) device.setTopicPrefix("streetlight");
        device.setDeleted(false);
        deviceService.save(device);

        saveAuditLog("DEVICE_CREATE", "DEVICE", device.getDeviceId(),
                "新增设备-" + device.getName(), "SUCCESS", httpRequest);
        log.info("[设备] 新增: deviceId={}, name={}", device.getDeviceId(), device.getName());
        return Result.success(device);
    }

    /**
     * 更新设备信息。
     */
    @PutMapping("/{deviceId}")
    @RequirePermission("device:update")
    public Result<Device> update(@PathVariable String deviceId,
                                 @RequestBody Device device,
                                 HttpServletRequest httpRequest) {
        Device existing = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, deviceId)
                .eq(Device::getDeleted, false)
                .one();
        if (existing == null) {
            saveAuditLog("DEVICE_UPDATE", "DEVICE", deviceId,
                    "设备不存在-更新失败", "FAIL", httpRequest);
            return Result.error(404, "设备不存在");
        }

        // 只更新允许修改的字段
        if (device.getName() != null) existing.setName(device.getName());
        if (device.getArea() != null) existing.setArea(device.getArea());
        if (device.getLocation() != null) existing.setLocation(device.getLocation());
        if (device.getStatus() != null) existing.setStatus(device.getStatus());
        if (device.getHealthScore() != null) existing.setHealthScore(device.getHealthScore());
        if (device.getTopicPrefix() != null) {
            existing.setTopicPrefix(device.getTopicPrefix().isBlank() ? "streetlight" : device.getTopicPrefix());
        }
        if (device.getEnabled() != null) existing.setEnabled(device.getEnabled());
        deviceService.updateById(existing);

        // 重新查询返回最新数据
        Device updated = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, deviceId)
                .eq(Device::getDeleted, false)
                .one();

        saveAuditLog("DEVICE_UPDATE", "DEVICE", deviceId,
                "更新设备-" + existing.getName(), "SUCCESS", httpRequest);
        log.info("[设备] 更新: deviceId={}, name={}", deviceId, existing.getName());
        return Result.success(updated);
    }

    /**
     * 删除设备（软删除）。
     */
    @DeleteMapping("/{deviceId}")
    @RequirePermission("device:delete")
    public Result<Void> delete(@PathVariable String deviceId,
                               HttpServletRequest httpRequest) {
        Device existing = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, deviceId)
                .eq(Device::getDeleted, false)
                .one();
        if (existing == null) {
            saveAuditLog("DEVICE_DELETE", "DEVICE", deviceId,
                    "设备不存在-删除失败", "FAIL", httpRequest);
            return Result.error("设备不存在");
        }

        existing.setDeleted(true);
        existing.setEnabled(false);
        existing.setStatus(0);
        deviceService.updateById(existing);

        saveAuditLog("DEVICE_DELETE", "DEVICE", deviceId,
                "删除设备-" + existing.getName(), "SUCCESS", httpRequest);
        log.info("[设备] 删除: deviceId={}, name={}", deviceId, existing.getName());
        return Result.success();
    }

    // ======================== 审计日志 ========================

    private void saveAuditLog(String action, String targetType, String targetId,
                              String detail, String result, HttpServletRequest request) {
        try {
            AuditLog logEntry = new AuditLog();
            String operator = SecurityContext.getCurrentUsername();
            logEntry.setOperator(operator != null ? operator : "UNKNOWN");
            logEntry.setAction(action);
            logEntry.setTargetType(targetType);
            logEntry.setTargetId(targetId);
            logEntry.setDetail(detail);
            logEntry.setResult(result);
            logEntry.setIpAddress(getClientIp(request));
            logEntry.setOperatedAt(LocalDateTime.now());
            auditLogMapper.insert(logEntry);
        } catch (Exception e) {
            log.error("审计日志记录失败: {}", e.getMessage());
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
