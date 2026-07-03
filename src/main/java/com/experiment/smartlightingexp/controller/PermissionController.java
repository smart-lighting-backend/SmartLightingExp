package com.experiment.smartlightingexp.controller;

import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.common.SecurityContext;
import com.experiment.smartlightingexp.entity.AuditLog;
import com.experiment.smartlightingexp.entity.Permission;
import com.experiment.smartlightingexp.mapper.AuditLogMapper;
import com.experiment.smartlightingexp.mapper.PermissionMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 权限管理控制器 — 权限增删改查。
 * 写操作记录审计日志，满足 IR-11 安全可信控制。
 */
@Slf4j
@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionMapper permissionMapper;
    private final AuditLogMapper auditLogMapper;

    /**
     * 查询所有权限。
     */
    @GetMapping
    public Result<List<Permission>> getAll() {
        List<Permission> list = permissionMapper.selectList(null);
        return Result.success(list);
    }

    /**
     * 查询单个权限详情。
     */
    @GetMapping("/{id}")
    public Result<Permission> getById(@PathVariable Long id) {
        Permission permission = permissionMapper.selectById(id);
        if (permission == null) {
            return Result.error("权限不存在");
        }
        return Result.success(permission);
    }

    /**
     * 新增权限。
     */
    @PostMapping
    public Result<Void> create(@RequestBody Permission permission,
                               HttpServletRequest httpRequest) {
        if (permission.getName() == null || permission.getName().isBlank()) {
            return Result.error("权限名称不能为空");
        }
        if (permission.getPermissionCode() == null || permission.getPermissionCode().isBlank()) {
            return Result.error("权限编码不能为空");
        }

        // 检查编码唯一性
        Permission exist = permissionMapper.selectList(null).stream()
                .filter(p -> p.getPermissionCode().equals(permission.getPermissionCode()))
                .findFirst().orElse(null);
        if (exist != null) {
            return Result.error("权限编码已存在");
        }

        permissionMapper.insert(permission);

        saveAuditLog("PERM_CREATE", "PERMISSION", String.valueOf(permission.getId()),
                "新增权限-" + permission.getName(), "SUCCESS", httpRequest);
        log.info("[权限] 新增: id={}, name={}, code={}", permission.getId(), permission.getName(), permission.getPermissionCode());
        return Result.success();
    }

    /**
     * 更新权限信息。
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id,
                               @RequestBody Permission permission,
                               HttpServletRequest httpRequest) {
        Permission existing = permissionMapper.selectById(id);
        if (existing == null) {
            saveAuditLog("PERM_UPDATE", "PERMISSION", String.valueOf(id),
                    "权限不存在-更新失败", "FAIL", httpRequest);
            return Result.error("权限不存在");
        }

        if (permission.getName() != null) existing.setName(permission.getName());
        if (permission.getPermissionCode() != null) existing.setPermissionCode(permission.getPermissionCode());
        if (permission.getDescription() != null) existing.setDescription(permission.getDescription());
        permissionMapper.updateById(existing);

        saveAuditLog("PERM_UPDATE", "PERMISSION", String.valueOf(id),
                "更新权限-" + existing.getName(), "SUCCESS", httpRequest);
        log.info("[权限] 更新: id={}, name={}", id, existing.getName());
        return Result.success();
    }

    /**
     * 删除权限。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               HttpServletRequest httpRequest) {
        Permission existing = permissionMapper.selectById(id);
        if (existing == null) {
            saveAuditLog("PERM_DELETE", "PERMISSION", String.valueOf(id),
                    "权限不存在-删除失败", "FAIL", httpRequest);
            return Result.error("权限不存在");
        }

        permissionMapper.deleteById(id);

        saveAuditLog("PERM_DELETE", "PERMISSION", String.valueOf(id),
                "删除权限-" + existing.getName(), "SUCCESS", httpRequest);
        log.info("[权限] 删除: id={}, name={}", id, existing.getName());
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
