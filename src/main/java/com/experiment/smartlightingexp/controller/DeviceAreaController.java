package com.experiment.smartlightingexp.controller;

import com.experiment.smartlightingexp.common.RequirePermission;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.common.SecurityContext;
import com.experiment.smartlightingexp.entity.AuditLog;
import com.experiment.smartlightingexp.entity.DeviceArea;
import com.experiment.smartlightingexp.mapper.AuditLogMapper;
import com.experiment.smartlightingexp.service.DeviceAreaService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 设备分区管理控制器 — 区域 CRUD + 树形结构。
 */
@Slf4j
@RestController
@RequestMapping("/api/device-areas")
@RequiredArgsConstructor
public class DeviceAreaController {

    private final DeviceAreaService deviceAreaService;
    private final AuditLogMapper auditLogMapper;

    /** 获取所有区域（平铺列表）。 */
    @RequirePermission("device_area:read")
    @GetMapping
    public Result<List<DeviceArea>> listAll() {
        return Result.success(deviceAreaService.lambdaQuery()
                .eq(DeviceArea::getEnabled, true)
                .orderByAsc(DeviceArea::getCreateTime)
                .list());
    }

    /** 获取区域树形结构。 */
    @RequirePermission("device_area:read")
    @GetMapping("/tree")
    public Result<List<Map<String, Object>>> getTree() {
        return Result.success(deviceAreaService.getAreaTree());
    }

    /** 查询单个区域。 */
    @RequirePermission("device_area:read")
    @GetMapping("/{id}")
    public Result<DeviceArea> getById(@PathVariable Long id) {
        DeviceArea area = deviceAreaService.getById(id);
        if (area == null) {
            return Result.error(404, "区域不存在");
        }
        return Result.success(area);
    }

    /** 新增区域。 */
    @RequirePermission("device_area:create")
    @PostMapping
    public Result<DeviceArea> create(@RequestBody DeviceArea area,
                                     HttpServletRequest request) {
        if (area.getName() == null || area.getName().isBlank()) {
            return Result.error(400, "区域名称不能为空");
        }
        area.setEnabled(area.getEnabled() == null || area.getEnabled());
        deviceAreaService.save(area);

        saveAuditLog("AREA_CREATE", "DEVICE_AREA", String.valueOf(area.getId()),
                "新增区域-" + area.getName(), "SUCCESS", request);
        log.info("[区域] 新增: id={}, name={}", area.getId(), area.getName());
        return Result.success(area);
    }

    /** 更新区域。 */
    @RequirePermission("device_area:update")
    @PutMapping("/{id}")
    public Result<DeviceArea> update(@PathVariable Long id,
                                     @RequestBody DeviceArea area,
                                     HttpServletRequest request) {
        DeviceArea existing = deviceAreaService.getById(id);
        if (existing == null) {
            saveAuditLog("AREA_UPDATE", "DEVICE_AREA", String.valueOf(id),
                    "区域不存在-更新失败", "FAIL", request);
            return Result.error(404, "区域不存在");
        }
        if (area.getName() != null) existing.setName(area.getName());
        if (area.getDescription() != null) existing.setDescription(area.getDescription());
        if (area.getParentId() != null) existing.setParentId(area.getParentId());
        if (area.getEnabled() != null) existing.setEnabled(area.getEnabled());
        deviceAreaService.updateById(existing);

        DeviceArea updated = deviceAreaService.getById(id);
        saveAuditLog("AREA_UPDATE", "DEVICE_AREA", String.valueOf(id),
                "更新区域-" + updated.getName(), "SUCCESS", request);
        log.info("[区域] 更新: id={}, name={}", id, updated.getName());
        return Result.success(updated);
    }

    /** 删除区域（需未被设备引用且无子区域）。 */
    @RequirePermission("device_area:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               HttpServletRequest request) {
        DeviceArea area = deviceAreaService.getById(id);
        if (area == null) {
            saveAuditLog("AREA_DELETE", "DEVICE_AREA", String.valueOf(id),
                    "区域不存在-删除失败", "FAIL", request);
            return Result.error(404, "区域不存在");
        }
        try {
            deviceAreaService.deleteArea(id);
        } catch (IllegalStateException e) {
            saveAuditLog("AREA_DELETE", "DEVICE_AREA", String.valueOf(id),
                    "删除失败-" + e.getMessage(), "FAIL", request);
            return Result.error(400, e.getMessage());
        }

        saveAuditLog("AREA_DELETE", "DEVICE_AREA", String.valueOf(id),
                "删除区域-" + area.getName(), "SUCCESS", request);
        log.info("[区域] 删除: id={}, name={}", id, area.getName());
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
