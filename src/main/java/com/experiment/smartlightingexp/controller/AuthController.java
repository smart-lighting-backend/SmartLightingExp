package com.experiment.smartlightingexp.controller;

import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.dto.LoginRequest;
import com.experiment.smartlightingexp.dto.LoginResponse;
import com.experiment.smartlightingexp.dto.MenuTreeNode;
import com.experiment.smartlightingexp.dto.RegisterRequest;
import com.experiment.smartlightingexp.entity.Role;
import com.experiment.smartlightingexp.entity.User;
import com.experiment.smartlightingexp.mapper.RoleMapper;
import com.experiment.smartlightingexp.mapper.UserMapper;
import com.experiment.smartlightingexp.service.AuditLogService;
import com.experiment.smartlightingexp.service.MenuService;
import com.experiment.smartlightingexp.service.PermissionService;
import com.experiment.smartlightingexp.service.UserService;
import com.experiment.smartlightingexp.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 认证控制器 — 用户登录、登出接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PermissionService permissionService;
    private final MenuService menuService;
    private final AuditLogService auditLogService;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private static final String SUPER_ADMIN_ROLE = "SUPER_ADMIN";

    /**
     * 用户登录 — 校验用户名密码，签发 JWT。
     *
     * @param request 登录请求（username, password）
     * @return 登录成功返回 token、用户名、角色和权限列表
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest) {
        // 1. 查询用户
        User user = userService.getByUsername(request.getUsername());
        if (user == null) {
            log.warn("[登录失败] 用户不存在: {}", request.getUsername());
            auditLog(request.getUsername(), "LOGIN", "SYSTEM", null,
                    "登录失败-用户不存在", "FAIL", getClientIp(httpRequest));
            return Result.error(401, "用户名或密码错误");
        }
        if (!user.getEnabled()) {
            log.warn("[登录失败] 账号已停用: {}", request.getUsername());
            auditLog(request.getUsername(), "LOGIN", "SYSTEM", null,
                    "登录失败-账号已停用", "FAIL", getClientIp(httpRequest));
            return Result.error(1003, "账号已停用，请联系管理员");
        }

        // 2. 校验密码（BCrypt 加密比对）
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("[登录失败] 密码错误: {}", request.getUsername());
            auditLog(request.getUsername(), "LOGIN", "SYSTEM", null,
                    "登录失败-密码错误", "FAIL", getClientIp(httpRequest));
            return Result.error(401, "用户名或密码错误");
        }

        // 3. 查询角色和权限
        Role role = roleMapper.selectById(user.getRoleId());
        if (role == null) {
            log.error("[登录失败] 用户角色不存在: userId={}, roleId={}", user.getId(), user.getRoleId());
            return Result.error(500, "用户角色配置异常");
        }

        List<String> permissions = getEffectivePermissions(role.getRoleCode(), user.getRoleId());

        // 4. 查询可见菜单
        List<MenuTreeNode> menus = menuService.getVisibleMenuTree(permissions);

        // 5. 签发 JWT
        String token = jwtUtil.generateToken(user.getUsername(), role.getRoleCode(), permissions);

        log.info("[登录成功] username={}, role={}, permissions={}",
                user.getUsername(), role.getRoleCode(), permissions.size());

        // 5. 更新最后登录信息
        String clientIp = getClientIp(httpRequest);
        user.setLastLoginIp(clientIp);
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        // 6. 审计日志
        auditLog(user.getUsername(), "LOGIN", "SYSTEM", null,
                "登录成功-角色:" + role.getRoleCode(), "SUCCESS", clientIp);

        return Result.success(new LoginResponse(token, user.getUsername(), user.getRealName(), user.getPhone(), user.getEmail(), role.getRoleCode(), role.getName(), permissions, menus));
    }

    /**
     * 获取当前用户信息（用于前端校验 Token 是否有效）。
     * 返回 Token、用户名、角色、权限列表、可见菜单树。
     */
    @GetMapping("/me")
    public Result<LoginResponse> me(@RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Result.error(401, "未登录或 Token 无效");
        }
        String token = authHeader.substring(7);
        if (!jwtUtil.isTokenValid(token)) {
            return Result.error(401, "Token 已过期或无效");
        }
        String username = jwtUtil.extractSubject(token);
        String roleCode = jwtUtil.extractRoleCode(token);
        User user = userService.getByUsername(username);
        // 从数据库动态查询权限（Token 中的已是旧数据，分配权限后新 Token 才能更新）
        List<String> permissions = getEffectivePermissions(roleCode);
        List<MenuTreeNode> menus = menuService.getVisibleMenuTree(permissions);

        // 查询角色中文名
        String roleName = null;
        if (user != null && user.getRoleId() != null) {
            Role role = roleMapper.selectById(user.getRoleId());
            if (role != null) {
                roleName = role.getName();
            }
        }

        // 查询用户详情（姓名、手机、邮箱）
        String realName = user != null ? user.getRealName() : null;
        String phone = user != null ? user.getPhone() : null;
        String email = user != null ? user.getEmail() : null;

        return Result.success(new LoginResponse(token, username, realName, phone, email, roleCode, roleName, permissions, menus));
    }

    /**
     * 用户注册 — 创建账号并自动登录。
     * 注册成功后直接签发 JWT 返回，无需再次登录。
     */
    @PostMapping("/register")
    public Result<LoginResponse> register(@RequestBody RegisterRequest request,
                                          HttpServletRequest httpRequest) {
        // 1. 校验必填
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return Result.error("用户名不能为空");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            return Result.error("密码不能为空");
        }
        if (request.getRoleId() == null) {
            return Result.error("请选择角色");
        }

        // 2. 用户名格式校验
        if (!request.getUsername().matches("^[a-zA-Z0-9_]+$")) {
            return Result.error("用户名只能包含字母、数字和下划线");
        }

        // 3. 密码长度
        if (request.getPassword().length() < 8) {
            return Result.error("密码至少 8 位");
        }

        // 4. 查重
        User exist = userService.getByUsername(request.getUsername());
        if (exist != null) {
            return Result.error("用户名已存在");
        }

        // 5. 校验角色
        Role role = roleMapper.selectById(request.getRoleId());
        if (role == null) {
            return Result.error("角色不存在");
        }
        if (SUPER_ADMIN_ROLE.equals(role.getRoleCode())) {
            return Result.error("不允许注册为超级管理员");
        }

        // 6. 创建用户
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRealName(request.getRealName());
        user.setPhone(request.getPhone());
        user.setRoleId(request.getRoleId());
        user.setEnabled(true);
        user.setDeleted(false);
        userService.save(user);

        // 7. 查询权限和菜单（类似登录）
        List<String> permissions = getEffectivePermissions(role.getRoleCode(), user.getRoleId());
        List<MenuTreeNode> menus = menuService.getVisibleMenuTree(permissions);

        // 8. 签发 JWT
        String token = jwtUtil.generateToken(user.getUsername(), role.getRoleCode(), permissions);

        log.info("[注册成功] username={}, role={}", user.getUsername(), role.getRoleCode());

        // 9. 审计日志
        String clientIp = getClientIp(httpRequest);
        auditLog(user.getUsername(), "REGISTER", "USER", String.valueOf(user.getId()),
                "注册成功-角色:" + role.getRoleCode(), "SUCCESS", clientIp);

        return Result.success(new LoginResponse(token, user.getUsername(), user.getRealName(), user.getPhone(), user.getEmail(), role.getRoleCode(), role.getName(), permissions, menus));
    }

    /**
     * 记录审计日志。
     */
    private List<String> getEffectivePermissions(String roleCode, Long roleId) {
        // SUPER_ADMIN 无需分配，自动拥有全部权限
        if ("SUPER_ADMIN".equals(roleCode)) {
            return permissionService.getAllPermissionCodes();
        }
        return permissionService.getPermissionCodesByRoleId(roleId);
    }

    private List<String> getEffectivePermissions(String roleCode) {
        // 所有角色统一从 role_permission 表动态查询
        return permissionService.getPermissionCodesByRoleCode(roleCode);
    }

    private void auditLog(String operator, String action, String targetType,
                          String targetId, String detail, String result, String ip) {
        try {
            com.experiment.smartlightingexp.entity.AuditLog logEntry =
                    new com.experiment.smartlightingexp.entity.AuditLog();
            logEntry.setOperator(operator);
            logEntry.setAction(action);
            logEntry.setTargetType(targetType);
            logEntry.setTargetId(targetId);
            logEntry.setDetail(detail);
            logEntry.setResult(result);
            logEntry.setIpAddress(ip);
            logEntry.setOperatedAt(LocalDateTime.now());
            auditLogService.save(logEntry);
        } catch (Exception e) {
            log.error("审计日志记录失败: {}", e.getMessage());
        }
    }

    /**
     * 获取客户端 IP 地址。
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多级代理时取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
