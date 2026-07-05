package com.experiment.smartlightingexp.controller;

import com.experiment.smartlightingexp.common.RequirePermission;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.common.SecurityContext;
import com.experiment.smartlightingexp.dto.PermissionTreeNode;
import com.experiment.smartlightingexp.entity.AuditLog;
import com.experiment.smartlightingexp.entity.Permission;
import com.experiment.smartlightingexp.mapper.AuditLogMapper;
import com.experiment.smartlightingexp.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 权限管理控制器 — 权限增删改查 + 树形结构。
 */
@Slf4j
@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;
    private final AuditLogMapper auditLogMapper;

    /** 前端 UI 未暴露操作的权限，不展示/返回给前端 */
    static final Set<String> HIDDEN_PERMISSION_CODES = Set.of(
            "menu:create", "menu:delete",
            "permission:create", "permission:delete"
    );

    /**
     * 获取权限树（用于前端权限分配树形选择器）。
     * 可选参数: roleId — 若传入，标记该角色已拥有的权限节点为 checked。
     */
    @RequirePermission("permission:read")
    @GetMapping("/tree")
    public Result<List<PermissionTreeNode>> getTree(@RequestParam(required = false) Long roleId) {
        List<Permission> allPermissions = permissionService.list();

        // 过滤掉前端 UI 未暴露的权限
        allPermissions = allPermissions.stream()
                .filter(p -> !HIDDEN_PERMISSION_CODES.contains(p.getPermissionCode()))
                .collect(Collectors.toList());

        // 该角色已拥有的权限ID集合
        Set<Long> checkedIds = new HashSet<>();
        if (roleId != null) {
            List<String> codes = permissionService.getPermissionCodesByRoleId(roleId);
            // 通过 permissionCode 找到对应 ID
            if (codes != null && !codes.isEmpty()) {
                checkedIds = allPermissions.stream()
                        .filter(p -> codes.contains(p.getPermissionCode()))
                        .map(Permission::getId)
                        .collect(Collectors.toSet());
            }
        }

        return Result.success(buildTree(allPermissions, null, checkedIds));
    }

    /**
     * 查询所有权限（扁平列表）。
     */
    @RequirePermission("permission:read")
    @GetMapping
    public Result<List<Permission>> list() {
        return Result.success(permissionService.list());
    }

    /**
     * 查询单个权限。
     */
    @RequirePermission("permission:read")
    @GetMapping("/{id}")
    public Result<Permission> getById(@PathVariable Long id) {
        Permission perm = permissionService.getById(id);
        if (perm == null) {
            return Result.error("权限不存在");
        }
        return Result.success(perm);
    }

    /**
     * 新增权限。
     */
    @RequirePermission("permission:create")
    @PostMapping
    public Result<Void> create(@RequestBody Permission permission, HttpServletRequest request) {
        if (permission.getName() == null || permission.getName().isBlank()) {
            return Result.error("权限名称不能为空");
        }
        if (permission.getPermissionCode() == null || permission.getPermissionCode().isBlank()) {
            return Result.error("权限编码不能为空");
        }
        permissionService.save(permission);
        saveAuditLog("PERM_CREATE", "PERMISSION", String.valueOf(permission.getId()),
                "新增权限-" + permission.getName(), "SUCCESS", request);
        log.info("[权限] 新增: id={}, name={}, code={}", permission.getId(), permission.getName(), permission.getPermissionCode());
        return Result.success();
    }

    /**
     * 修改权限。
     */
    @RequirePermission("permission:update")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Permission permission,
                               HttpServletRequest request) {
        Permission existing = permissionService.getById(id);
        if (existing == null) {
            saveAuditLog("PERM_UPDATE", "PERMISSION", String.valueOf(id),
                    "权限不存在-更新失败", "FAIL", request);
            return Result.error("权限不存在");
        }

        if (permission.getName() != null) existing.setName(permission.getName());
        if (permission.getPermissionCode() != null) existing.setPermissionCode(permission.getPermissionCode());
        if (permission.getDescription() != null) existing.setDescription(permission.getDescription());
        if (permission.getParentId() != null) existing.setParentId(permission.getParentId());
        if (permission.getType() != null) existing.setType(permission.getType());

        permissionService.updateById(existing);
        saveAuditLog("PERM_UPDATE", "PERMISSION", String.valueOf(id),
                "更新权限-" + existing.getName(), "SUCCESS", request);
        log.info("[权限] 更新: id={}, name={}", id, existing.getName());
        return Result.success();
    }

    /**
     * 删除权限。
     */
    @RequirePermission("permission:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        Permission existing = permissionService.getById(id);
        if (existing == null) {
            saveAuditLog("PERM_DELETE", "PERMISSION", String.valueOf(id),
                    "权限不存在-删除失败", "FAIL", request);
            return Result.error("权限不存在");
        }
        permissionService.removeById(id);
        saveAuditLog("PERM_DELETE", "PERMISSION", String.valueOf(id),
                "删除权限-" + existing.getName(), "SUCCESS", request);
        log.info("[权限] 删除: id={}, name={}", id, existing.getName());
        return Result.success();
    }

    // ======================== 工具方法 ========================

    /**
     * 递归构建权限树。
     */
    private List<PermissionTreeNode> buildTree(List<Permission> permissions,
                                                Long parentId, Set<Long> checkedIds) {
        List<PermissionTreeNode> nodes = new ArrayList<>();
        for (Permission perm : permissions) {
            if (!Objects.equals(perm.getParentId(), parentId)) continue;

            PermissionTreeNode node = new PermissionTreeNode();
            node.setId(perm.getId());
            node.setParentId(perm.getParentId());
            node.setName(perm.getName());
            node.setPermissionCode(perm.getPermissionCode());
            node.setType(perm.getType() != null ? perm.getType() : "ACTION");
            node.setDescription(perm.getDescription());
            node.setChecked(checkedIds.contains(perm.getId()));
            node.setChildren(buildTree(permissions, perm.getId(), checkedIds));
            nodes.add(node);
        }
        return nodes;
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
