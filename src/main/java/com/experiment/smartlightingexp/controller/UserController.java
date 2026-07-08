package com.experiment.smartlightingexp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.experiment.smartlightingexp.common.RequirePermission;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.common.SecurityContext;
import com.experiment.smartlightingexp.dto.UserQueryRequest;
import com.experiment.smartlightingexp.entity.AuditLog;
import com.experiment.smartlightingexp.entity.Role;
import com.experiment.smartlightingexp.entity.User;
import com.experiment.smartlightingexp.mapper.AuditLogMapper;
import com.experiment.smartlightingexp.mapper.RoleMapper;
import com.experiment.smartlightingexp.mapper.UserMapper;
import com.experiment.smartlightingexp.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
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
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final AuditLogMapper auditLogMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * 组合条件分页查询用户列表。
     * 支持按 roleId、username、realName、phone、department 等筛选。
     */
    @RequirePermission("user:read")
    @PostMapping("/list")
    public Result<IPage<Map<String, Object>>> list(@RequestBody UserQueryRequest request) {
        LambdaQueryWrapper<User> wrapper = buildQueryWrapper(request);
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
        AtomicLong displayIdCounter = new AtomicLong((result.getCurrent() - 1) * result.getSize() + 1);
        List<Map<String, Object>> records = result.getRecords().stream().map(user -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("displayId", displayIdCounter.getAndIncrement());
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
    @RequirePermission("user:read")
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
    @RequirePermission("user:create")
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
    @RequirePermission("user:update")
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
     * 停用用户。
     */
    @RequirePermission("user:update")
    @PutMapping("/{id}/disable")
    public Result<Void> disable(@PathVariable Long id,
                                HttpServletRequest httpRequest) {
        User existing = userService.lambdaQuery()
                .eq(User::getId, id)
                .eq(User::getDeleted, false)
                .one();
        if (existing == null) {
            saveAuditLog("USER_DISABLE", "USER", String.valueOf(id),
                    "用户不存在-停用失败", "FAIL", httpRequest);
            return Result.error("用户不存在");
        }

        // 保护超级管理员
        if (isSuperAdminUser(existing)) {
            return Result.error("超级管理员不可停用");
        }

        existing.setEnabled(false);
        userService.updateById(existing);

        saveAuditLog("USER_DISABLE", "USER", String.valueOf(id),
                "停用用户-" + existing.getUsername(), "SUCCESS", httpRequest);
        log.info("[用户] 停用: id={}, username={}", id, existing.getUsername());
        return Result.success();
    }

    /**
     * 物理删除用户。
     */
    @RequirePermission("user:delete")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               HttpServletRequest httpRequest) {
        User existing = userService.lambdaQuery()
                .eq(User::getId, id)
                .eq(User::getDeleted, false)
                .one();
        if (existing == null) {
            saveAuditLog("USER_DELETE", "USER", String.valueOf(id),
                    "用户不存在-物理删除失败", "FAIL", httpRequest);
            return Result.error("用户不存在");
        }

        // 保护超级管理员
        if (isSuperAdminUser(existing)) {
            return Result.error("超级管理员不可删除");
        }

        int deletedRows = userMapper.physicalDeleteById(id);
        if (deletedRows == 0) {
            saveAuditLog("USER_DELETE", "USER", String.valueOf(id),
                    "物理删除用户失败-" + existing.getUsername(), "FAIL", httpRequest);
            return Result.error("删除失败");
        }

        saveAuditLog("USER_DELETE", "USER", String.valueOf(id),
                "物理删除用户-" + existing.getUsername(), "SUCCESS", httpRequest);
        log.info("[用户] 物理删除: id={}, username={}", id, existing.getUsername());
        return Result.success();
    }

    /**
     * 获取所有角色列表（供前端下拉选择）。
     */
    @RequirePermission("user:read")
    @GetMapping("/roles")
    public Result<List<Role>> getAllRoles() {
        List<Role> roles = roleMapper.selectList(null);
        return Result.success(roles);
    }

    /**
     * 批量删除用户（物理删除）。
     */
    @RequirePermission("user:delete")
    @DeleteMapping("/batch")
    public Result<Void> batchDelete(@RequestBody List<Long> ids,
                                    HttpServletRequest httpRequest) {
        if (ids == null || ids.isEmpty()) {
            return Result.error("请选择要删除的用户");
        }

        // 保护超级管理员
        List<User> superAdmins = userService.lambdaQuery()
                .in(User::getId, ids)
                .eq(User::getDeleted, false)
                .list()
                .stream()
                .filter(this::isSuperAdminUser)
                .collect(Collectors.toList());
        if (!superAdmins.isEmpty()) {
            String names = superAdmins.stream().map(User::getUsername).collect(Collectors.joining(","));
            return Result.error("超级管理员(" + names + ")不可删除");
        }

        for (Long id : ids) {
            userMapper.physicalDeleteById(id);
        }
        saveAuditLog("USER_BATCH_DELETE", "USER",
                ids.stream().map(String::valueOf).collect(Collectors.joining(",")),
                "批量删除用户-" + ids.size() + "个", "SUCCESS", httpRequest);
        log.info("[用户] 批量删除: ids={}, count={}", ids, ids.size());
        return Result.success();
    }

    /**
     * 批量导出用户 Excel。
     * 根据查询条件导出所有匹配用户（不分页）。
     */
    @RequirePermission("user:read")
    @PostMapping("/export")
    public void export(@RequestBody UserQueryRequest request,
                       HttpServletResponse response) throws IOException {
        LambdaQueryWrapper<User> wrapper = buildQueryWrapper(request);
        wrapper.orderByAsc(User::getId);
        List<User> users = userService.list(wrapper);

        Set<Long> roleIds = users.stream()
                .map(User::getRoleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> roleNameMap = new HashMap<>();
        if (!roleIds.isEmpty()) {
            roleMapper.selectBatchIds(roleIds)
                    .forEach(r -> roleNameMap.put(r.getId(), r.getName()));
        }

        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("用户数据");
        sheet.setColumnWidth(0, 3000);
        sheet.setColumnWidth(1, 2500);
        sheet.setColumnWidth(2, 3000);
        sheet.setColumnWidth(3, 6000);
        sheet.setColumnWidth(4, 4000);
        sheet.setColumnWidth(5, 3000);
        sheet.setColumnWidth(6, 3000);
        sheet.setColumnWidth(7, 2000);
        sheet.setColumnWidth(8, 4000);
        sheet.setColumnWidth(9, 5000);
        sheet.setColumnWidth(10, 5000);

        // 表头样式
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setFontHeightInPoints((short) 12);
        headerStyle.setFont(headerFont);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);

        // 表头
        String[] headers = {"用户名", "姓名", "手机号", "邮箱", "部门", "区域编码", "角色", "状态", "最后登录IP", "最后登录时间", "创建时间"};
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // 数据行
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        int rowIdx = 1;
        for (User user : users) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(user.getUsername() != null ? user.getUsername() : "");
            row.createCell(1).setCellValue(user.getRealName() != null ? user.getRealName() : "");
            row.createCell(2).setCellValue(user.getPhone() != null ? user.getPhone() : "");
            row.createCell(3).setCellValue(user.getEmail() != null ? user.getEmail() : "");
            row.createCell(4).setCellValue(user.getDepartment() != null ? user.getDepartment() : "");
            row.createCell(5).setCellValue(user.getAreaCode() != null ? user.getAreaCode() : "");
            row.createCell(6).setCellValue(roleNameMap.getOrDefault(user.getRoleId(), ""));
            row.createCell(7).setCellValue(Boolean.TRUE.equals(user.getEnabled()) ? "启用" : "停用");
            row.createCell(8).setCellValue(user.getLastLoginIp() != null ? user.getLastLoginIp() : "");
            row.createCell(9).setCellValue(user.getLastLoginTime() != null ? user.getLastLoginTime().format(dtf) : "");
            row.createCell(10).setCellValue(user.getCreateTime() != null ? user.getCreateTime().format(dtf) : "");
        }

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"users_" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx\"");
        workbook.write(response.getOutputStream());
        workbook.close();
    }

    // ======================== 辅助方法 ========================

    /** 超级管理员角色编码 */
    private static final String SUPER_ADMIN_ROLE_CODE = "SUPER_ADMIN";

    /**
     * 判断用户是否为超级管理员。
     */
    private boolean isSuperAdminUser(User user) {
        if (user == null || user.getRoleId() == null) return false;
        Role role = roleMapper.selectById(user.getRoleId());
        return role != null && SUPER_ADMIN_ROLE_CODE.equals(role.getRoleCode());
    }

    // ======================== 查询条件构建 ========================

    /**
     * 构建用户列表查询条件（排除已删除）。
     */
    private LambdaQueryWrapper<User> buildQueryWrapper(UserQueryRequest request) {
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
        return wrapper;
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
