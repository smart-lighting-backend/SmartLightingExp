package com.experiment.smartlightingexp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.common.SecurityContext;
import com.experiment.smartlightingexp.dto.UserQueryRequest;
import com.experiment.smartlightingexp.entity.AuditLog;
import com.experiment.smartlightingexp.entity.Role;
import com.experiment.smartlightingexp.entity.User;
import com.experiment.smartlightingexp.mapper.AuditLogMapper;
import com.experiment.smartlightingexp.mapper.RoleMapper;
import com.experiment.smartlightingexp.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户管理控制器 — 用户增删改查 + 角色等多条件组合分页查询。
 * 写操作记录审计日志，满足 IR-11 安全可信控制。
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final RoleMapper roleMapper;
    private final AuditLogMapper auditLogMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * 组合条件分页查询用户列表。
     * 支持按 roleId、username、realName、phone、department 等筛选。
     */
    @PostMapping("/list")
    public Result<IPage<Map<String, Object>>> list(@RequestBody UserQueryRequest request) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getDeleted, false);

        if (request.getRoleId() != null) {
            wrapper.eq(User::getRoleId, request.getRoleId());
        }
        if (request.getUsername() != null && !request.getUsername().isBlank()) {
            wrapper.like(User::getUsername, request.getUsername());
        }
        if (request.getRealName() != null && !request.getRealName().isBlank()) {
            wrapper.like(User::getRealName, request.getRealName());
        }
        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            wrapper.like(User::getPhone, request.getPhone());
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            wrapper.like(User::getEmail, request.getEmail());
        }
        if (request.getDepartment() != null && !request.getDepartment().isBlank()) {
            wrapper.eq(User::getDepartment, request.getDepartment());
        }
        if (request.getAreaCode() != null && !request.getAreaCode().isBlank()) {
            wrapper.eq(User::getAreaCode, request.getAreaCode());
        }
        if (request.getEnabled() != null) {
            wrapper.eq(User::getEnabled, request.getEnabled());
        }

        wrapper.orderByAsc(User::getId);

        Page<User> page = new Page<>(request.getPage(), request.getSize());
        IPage<User> result = userService.page(page, wrapper);

        // 获取所有 roleId 对应的角色信息
        Set<Long> roleIds = result.getRecords().stream()
                .map(User::getRoleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Role> roleMap = new HashMap<>();
        if (!roleIds.isEmpty()) {
            roleMapper.selectBatchIds(roleIds).forEach(r -> roleMap.put(r.getId(), r));
        }

        // 组装返回结果（隐藏密码，附加角色信息）
        List<Map<String, Object>> records = result.getRecords().stream().map(user -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", user.getId());
            item.put("username", user.getUsername());
            item.put("realName", user.getRealName());
            item.put("phone", user.getPhone());
            item.put("email", user.getEmail());
            item.put("department", user.getDepartment());
            item.put("areaCode", user.getAreaCode());
            item.put("roleId", user.getRoleId());
            item.put("enabled", user.getEnabled());
            item.put("lastLoginIp", user.getLastLoginIp());
            item.put("lastLoginTime", user.getLastLoginTime());
            item.put("createTime", user.getCreateTime());
            item.put("updateTime", user.getUpdateTime());

            Role role = roleMap.get(user.getRoleId());
            if (role != null) {
                item.put("roleName", role.getName());
                item.put("roleCode", role.getRoleCode());
            }
            return item;
        }).collect(Collectors.toList());

        // 构造分页结果
        Page<Map<String, Object>> pageResult = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        pageResult.setRecords(records);

        log.info("[用户查询] 条件: roleId={}, username={}, department={}, 结果数={}",
                request.getRoleId(), request.getUsername(), request.getDepartment(), records.size());
        return Result.success(pageResult);
    }

    /**
     * 查询单个用户详情。
     */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> getById(@PathVariable Long id) {
        User user = userService.lambdaQuery()
                .eq(User::getId, id)
                .eq(User::getDeleted, false)
                .one();
        if (user == null) {
            return Result.error("用户不存在");
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", user.getId());
        item.put("username", user.getUsername());
        item.put("realName", user.getRealName());
        item.put("phone", user.getPhone());
        item.put("email", user.getEmail());
        item.put("department", user.getDepartment());
        item.put("areaCode", user.getAreaCode());
        item.put("roleId", user.getRoleId());
        item.put("enabled", user.getEnabled());
        item.put("lastLoginIp", user.getLastLoginIp());
        item.put("lastLoginTime", user.getLastLoginTime());
        item.put("createTime", user.getCreateTime());
        item.put("updateTime", user.getUpdateTime());

        if (user.getRoleId() != null) {
            Role role = roleMapper.selectById(user.getRoleId());
            if (role != null) {
                item.put("roleName", role.getName());
                item.put("roleCode", role.getRoleCode());
            }
        }

        return Result.success(item);
    }

    /**
     * 新增用户。
     */
    @PostMapping
    public Result<Void> create(@RequestBody User user,
                               HttpServletRequest httpRequest) {
        if (user.getUsername() == null || user.getUsername().isBlank()) {
            return Result.error("用户名不能为空");
        }
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            return Result.error("密码不能为空");
        }

        User exist = userService.getByUsername(user.getUsername());
        if (exist != null) {
            return Result.error("用户名已存在");
        }

        user.setPassword(passwordEncoder.encode(user.getPassword()));
        if (user.getEnabled() == null) user.setEnabled(true);
        user.setDeleted(false);
        userService.save(user);

        saveAuditLog("USER_CREATE", "USER", String.valueOf(user.getId()),
                "新增用户-" + user.getUsername(), "SUCCESS", httpRequest);
        log.info("[用户] 新增: id={}, username={}", user.getId(), user.getUsername());
        return Result.success();
    }

    /**
     * 更新用户信息。
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id,
                               @RequestBody User user,
                               HttpServletRequest httpRequest) {
        User existing = userService.lambdaQuery()
                .eq(User::getId, id)
                .eq(User::getDeleted, false)
                .one();
        if (existing == null) {
            saveAuditLog("USER_UPDATE", "USER", String.valueOf(id),
                    "用户不存在-更新失败", "FAIL", httpRequest);
            return Result.error("用户不存在");
        }

        if (user.getRealName() != null) existing.setRealName(user.getRealName());
        if (user.getPhone() != null) existing.setPhone(user.getPhone());
        if (user.getEmail() != null) existing.setEmail(user.getEmail());
        if (user.getDepartment() != null) existing.setDepartment(user.getDepartment());
        if (user.getAreaCode() != null) existing.setAreaCode(user.getAreaCode());
        if (user.getRoleId() != null) existing.setRoleId(user.getRoleId());
        if (user.getEnabled() != null) existing.setEnabled(user.getEnabled());
        if (user.getPassword() != null && !user.getPassword().isBlank()) {
            existing.setPassword(passwordEncoder.encode(user.getPassword()));
        }

        userService.updateById(existing);

        saveAuditLog("USER_UPDATE", "USER", String.valueOf(id),
                "更新用户-" + existing.getUsername(), "SUCCESS", httpRequest);
        log.info("[用户] 更新: id={}, username={}", id, existing.getUsername());
        return Result.success();
    }

    /**
     * 删除用户。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               HttpServletRequest httpRequest) {
        User existing = userService.lambdaQuery()
                .eq(User::getId, id)
                .eq(User::getDeleted, false)
                .one();
        if (existing == null) {
            saveAuditLog("USER_DELETE", "USER", String.valueOf(id),
                    "用户不存在-删除失败", "FAIL", httpRequest);
            return Result.error("用户不存在");
        }

        existing.setDeleted(true);
        existing.setEnabled(false);
        userService.updateById(existing);

        saveAuditLog("USER_DELETE", "USER", String.valueOf(id),
                "删除用户-" + existing.getUsername(), "SUCCESS", httpRequest);
        log.info("[用户] 删除: id={}, username={}", id, existing.getUsername());
        return Result.success();
    }

    /**
     * 获取所有角色列表（供前端下拉选择）。
     */
    @GetMapping("/roles")
    public Result<List<Role>> getAllRoles() {
        List<Role> roles = roleMapper.selectList(null);
        return Result.success(roles);
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
