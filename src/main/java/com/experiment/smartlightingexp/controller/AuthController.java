package com.experiment.smartlightingexp.controller;

import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.dto.LoginRequest;
import com.experiment.smartlightingexp.dto.LoginResponse;
import com.experiment.smartlightingexp.dto.MenuTreeNode;
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
        if (user == null || !user.getEnabled()) {
            log.warn("[登录失败] 用户不存在或已禁用: {}", request.getUsername());
            auditLog(request.getUsername(), "LOGIN", "SYSTEM", null,
                    "登录失败-用户不存在或已禁用", "FAIL", getClientIp(httpRequest));
            return Result.error(401, "用户名或密码错误");
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

        List<String> permissions = permissionService.getPermissionCodesByRoleId(user.getRoleId());

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

        return Result.success(new LoginResponse(token, user.getUsername(), role.getRoleCode(), permissions, menus));
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
        List<String> permissions = jwtUtil.extractPermissions(token);
        List<MenuTreeNode> menus = menuService.getVisibleMenuTree(permissions);

        return Result.success(new LoginResponse(token, username, roleCode, permissions, menus));
    }

    /**
     * 记录审计日志。
     */
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
