package com.experiment.smartlightingexp.controller;

import com.experiment.smartlightingexp.common.RequirePermission;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.common.SecurityContext;
import com.experiment.smartlightingexp.dto.MenuTreeNode;
import com.experiment.smartlightingexp.entity.AuditLog;
import com.experiment.smartlightingexp.entity.Menu;
import com.experiment.smartlightingexp.mapper.AuditLogMapper;
import com.experiment.smartlightingexp.service.MenuService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 菜单管理控制器 — 动态菜单的增删改查 + 树形结构。
 */
@Slf4j
@RestController
@RequestMapping("/api/menus")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;
    private final AuditLogMapper auditLogMapper;

    /**
     * 获取完整菜单树（后台管理用）。
     */
    @RequirePermission("menu:read")
    @GetMapping("/tree")
    public Result<List<MenuTreeNode>> getTree() {
        return Result.success(menuService.getMenuTree());
    }

    /**
     * 获取当前用户可见的菜单树（前端导航渲染用）。
     */
    @GetMapping("/visible")
    public Result<List<MenuTreeNode>> getVisibleTree() {
        List<String> permissions = SecurityContext.getCurrentPermissions();
        return Result.success(menuService.getVisibleMenuTree(permissions));
    }

    /**
     * 查询所有菜单（扁平列表）。
     */
    @RequirePermission("menu:read")
    @GetMapping
    public Result<List<Menu>> list() {
        return Result.success(menuService.list());
    }

    /**
     * 新增菜单。
     */
    @RequirePermission("menu:create")
    @PostMapping
    public Result<Void> create(@RequestBody Menu menu, HttpServletRequest request) {
        if (menu.getName() == null || menu.getName().isBlank()) {
            return Result.error("菜单名称不能为空");
        }
        menuService.save(menu);
        saveAuditLog("MENU_CREATE", "MENU", String.valueOf(menu.getId()),
                "新增菜单-" + menu.getName(), "SUCCESS", request);
        log.info("[菜单] 新增: id={}, name={}, path={}", menu.getId(), menu.getName(), menu.getPath());
        return Result.success();
    }

    /**
     * 修改菜单。
     */
    @RequirePermission("menu:update")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Menu menu,
                               HttpServletRequest request) {
        Menu existing = menuService.getById(id);
        if (existing == null) {
            saveAuditLog("MENU_UPDATE", "MENU", String.valueOf(id),
                    "菜单不存在-更新失败", "FAIL", request);
            return Result.error("菜单不存在");
        }

        if (menu.getName() != null) existing.setName(menu.getName());
        if (menu.getPermissionCode() != null) existing.setPermissionCode(menu.getPermissionCode());
        if (menu.getIcon() != null) existing.setIcon(menu.getIcon());
        if (menu.getPath() != null) existing.setPath(menu.getPath());
        if (menu.getComponent() != null) existing.setComponent(menu.getComponent());
        if (menu.getSort() != null) existing.setSort(menu.getSort());
        if (menu.getEnabled() != null) existing.setEnabled(menu.getEnabled());
        existing.setParentId(menu.getParentId()); // 允许设为 null（子→一级）

        menuService.updateById(existing);
        saveAuditLog("MENU_UPDATE", "MENU", String.valueOf(id),
                "更新菜单-" + existing.getName(), "SUCCESS", request);
        log.info("[菜单] 更新: id={}, name={}", id, existing.getName());
        return Result.success();
    }

    /**
     * 删除菜单。
     */
    @RequirePermission("menu:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        Menu existing = menuService.getById(id);
        if (existing == null) {
            saveAuditLog("MENU_DELETE", "MENU", String.valueOf(id),
                    "菜单不存在-删除失败", "FAIL", request);
            return Result.error("菜单不存在");
        }
        menuService.removeById(id);
        saveAuditLog("MENU_DELETE", "MENU", String.valueOf(id),
                "删除菜单-" + existing.getName(), "SUCCESS", request);
        log.info("[菜单] 删除: id={}, name={}", id, existing.getName());
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
