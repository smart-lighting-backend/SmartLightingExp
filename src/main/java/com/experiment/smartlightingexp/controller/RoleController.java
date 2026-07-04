package com.experiment.smartlightingexp.controller;

import com.experiment.smartlightingexp.common.RequirePermission;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.common.SecurityContext;
import com.experiment.smartlightingexp.entity.AuditLog;
import com.experiment.smartlightingexp.entity.Permission;
import com.experiment.smartlightingexp.entity.Role;
import com.experiment.smartlightingexp.mapper.AuditLogMapper;
import com.experiment.smartlightingexp.mapper.PermissionMapper;
import com.experiment.smartlightingexp.service.RoleService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 角色管理控制器 — 角色增删改查 + 权限分配。
 * 写操作记录审计日志，满足 IR-11 安全可信控制。
 */
@Slf4j
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;
    private final PermissionMapper permissionMapper;
    private final AuditLogMapper auditLogMapper;

    /**
     * 查询所有角色（含权限编码列表）。
     */
    @GetMapping
    public Result<List<Map<String, Object>>> getAll() {
        List<Role> roles = roleService.list();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Role role : roles) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", role.getId());
            item.put("name", role.getName());
            item.put("roleCode", role.getRoleCode());
            item.put("description", role.getDescription());
            item.put("permissionCodes", roleService.getPermissionCodesByRoleId(role.getId()));
            item.put("createTime", role.getCreateTime());
            result.add(item);
        }

        return Result.success(result);
    }

    /**
     * 查询单个角色详情（含权限）。
     */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> getById(@PathVariable Long id) {
        Role role = roleService.getById(id);
        if (role == null) {
            return Result.error("角色不存在");
        }

        // 查权限编码
        List<String> permissionCodes = roleService.getPermissionCodesByRoleId(id);
        // 查权限详情（ID + 编码 + 名称）
        List<Permission> allPermissions = permissionMapper.selectList(null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", role.getId());
        result.put("name", role.getName());
        result.put("roleCode", role.getRoleCode());
        result.put("description", role.getDescription());
        result.put("permissionCodes", permissionCodes);
        result.put("allPermissions", allPermissions);
        result.put("createTime", role.getCreateTime());

        return Result.success(result);
    }

    /**
     * 新增角色。
     */
    @RequirePermission("role:create")
    @PostMapping
    public Result<Void> create(@RequestBody Role role,
                               HttpServletRequest httpRequest) {
        if (role.getName() == null || role.getName().isBlank()) {
            return Result.error("角色名称不能为空");
        }
        if (role.getRoleCode() == null || role.getRoleCode().isBlank()) {
            return Result.error("角色编码不能为空");
        }

        Role exist = roleService.getByRoleCode(role.getRoleCode());
        if (exist != null) {
            return Result.error("角色编码已存在");
        }

        roleService.save(role);

        saveAuditLog("ROLE_CREATE", "ROLE", String.valueOf(role.getId()),
                "新增角色-" + role.getName(), "SUCCESS", httpRequest);
        log.info("[角色] 新增: id={}, name={}, code={}", role.getId(), role.getName(), role.getRoleCode());
        return Result.success();
    }

    /**
     * 更新角色信息。
     */
    @RequirePermission("role:update")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id,
                               @RequestBody Role role,
                               HttpServletRequest httpRequest) {
        Role existing = roleService.getById(id);
        if (existing == null) {
            saveAuditLog("ROLE_UPDATE", "ROLE", String.valueOf(id),
                    "角色不存在-更新失败", "FAIL", httpRequest);
            return Result.error("角色不存在");
        }

        if (role.getName() != null) existing.setName(role.getName());
        if (role.getDescription() != null) existing.setDescription(role.getDescription());
        roleService.updateById(existing);

        saveAuditLog("ROLE_UPDATE", "ROLE", String.valueOf(id),
                "更新角色-" + existing.getName(), "SUCCESS", httpRequest);
        log.info("[角色] 更新: id={}, name={}", id, existing.getName());
        return Result.success();
    }

    /**
     * 删除角色。
     */
    @RequirePermission("role:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               HttpServletRequest httpRequest) {
        Role existing = roleService.getById(id);
        if (existing == null) {
            saveAuditLog("ROLE_DELETE", "ROLE", String.valueOf(id),
                    "角色不存在-删除失败", "FAIL", httpRequest);
            return Result.error("角色不存在");
        }

        roleService.removeById(id);

        saveAuditLog("ROLE_DELETE", "ROLE", String.valueOf(id),
                "删除角色-" + existing.getName(), "SUCCESS", httpRequest);
        log.info("[角色] 删除: id={}, name={}", id, existing.getName());
        return Result.success();
    }

    /**
     * 分配角色权限（覆盖式：先清空旧权限，再设置新权限）。
     */
    @RequirePermission("role:assign")
    @PutMapping("/{id}/permissions")
    public Result<Void> assignPermissions(@PathVariable Long id,
                                          @RequestBody Map<String, List<Long>> body,
                                          HttpServletRequest httpRequest) {
        Role existing = roleService.getById(id);
        if (existing == null) {
            saveAuditLog("ROLE_ASSIGN_PERM", "ROLE", String.valueOf(id),
                    "角色不存在-权限分配失败", "FAIL", httpRequest);
            return Result.error("角色不存在");
        }

        List<Long> permissionIds = body.get("permissionIds");
        roleService.assignPermissions(id, permissionIds != null ? permissionIds : List.of());

        // 查权限名称用于审计日志
        String permNames = "";
        if (permissionIds != null && !permissionIds.isEmpty()) {
            List<Permission> perms = permissionMapper.selectBatchIds(permissionIds);
            permNames = perms.stream().map(Permission::getName).reduce((a, b) -> a + "," + b).orElse("");
        }

        saveAuditLog("ROLE_ASSIGN_PERM", "ROLE", String.valueOf(id),
                "分配权限-" + existing.getName() + ": " + permNames, "SUCCESS", httpRequest);
        log.info("[角色] 权限分配: roleId={}, permCount={}", id, permissionIds != null ? permissionIds.size() : 0);
        return Result.success();
    }

    /**
     * 查询所有权限（供权限分配下拉选择）。
     */
    @GetMapping("/permissions")
    public Result<List<Permission>> getAllPermissions() {
        List<Permission> permissions = permissionMapper.selectList(null);
        return Result.success(permissions);
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
