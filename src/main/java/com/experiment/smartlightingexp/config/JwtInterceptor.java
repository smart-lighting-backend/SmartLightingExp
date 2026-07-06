package com.experiment.smartlightingexp.config;

import com.experiment.smartlightingexp.common.RequirePermission;
import com.experiment.smartlightingexp.common.SecurityContext;
import com.experiment.smartlightingexp.mapper.PermissionMapper;
import com.experiment.smartlightingexp.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.util.List;

/**
 * JWT 拦截器 — 校验请求头中的 Token，解析用户信息并注入 SecurityContext。
 * 白名单路径（无需登录）：
 *   - /api/auth/login
 *   - /doc.html, /v3/api-docs, /swagger-resources, /webjars
 *   - /error
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final PermissionMapper permissionMapper;
    private static final String SUPER_ADMIN_ROLE = "SUPER_ADMIN";

    /**
     * 白名单路径前缀 — 匹配开头即放行。
     */
    private static final List<String> WHITE_LIST = List.of(
            "/api/auth/login",
            "/doc.html",
            "/v3/api-docs",
            "/swagger-resources",
            "/webjars",
            "/error",
            "/favicon.ico"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();

        // 1. 白名单放行
        if (isWhiteListed(path)) {
            return true;
        }

        // 2. 从 Header 获取 Token
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("[JWT拦截] 缺少Token或格式错误: {}", path);
            response.setStatus(401);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"未登录，请先登录\",\"data\":null}");
            return false;
        }

        String token = authHeader.substring(7);

        // 3. 校验 Token
        if (!jwtUtil.isTokenValid(token)) {
            log.warn("[JWT拦截] Token无效或已过期: {}", path);
            response.setStatus(401);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"Token已过期或无效，请重新登录\",\"data\":null}");
            return false;
        }

        // 4. 从 Token 中解析基础信息，但权限从数据库动态查询（分配权限后即时生效）
        String username = jwtUtil.extractSubject(token);
        String roleCode = jwtUtil.extractRoleCode(token);
        List<String> permissions = getEffectivePermissions(roleCode);

        SecurityContext.setCurrentUser(
                new SecurityContext.UserInfo(username, roleCode, permissions));

        // 5. 校验方法级权限（@RequirePermission 注解）
        if (handler instanceof HandlerMethod) {
            HandlerMethod handlerMethod = (HandlerMethod) handler;
            RequirePermission annotation = handlerMethod.getMethodAnnotation(RequirePermission.class);
            if (annotation != null) {
                String requiredPermission = annotation.value();
                if (!permissions.contains(requiredPermission)) {
                    log.warn("[权限拦截] 用户 {} 缺少权限: {}, 路径: {}",
                            username, requiredPermission, path);
                    response.setStatus(403);
                    response.setContentType("application/json;charset=utf-8");
                    response.getWriter().write(
                            "{\"code\":403,\"msg\":\"权限不足，需要 " + requiredPermission + "\",\"data\":null}");
                    SecurityContext.clear();
                    return false;
                }
                log.debug("[权限拦截] 用户 {} 通过权限校验: {}", username, requiredPermission);
            }
        }

        log.debug("[JWT拦截] 用户已认证: username={}, role={}", username, roleCode);
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler, ModelAndView modelAndView) {
        // 无需处理
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 请求结束后必须清除 ThreadLocal，防止内存泄漏
        SecurityContext.clear();
    }

    /**
     * 判断路径是否在白名单中。
     */
    private boolean isWhiteListed(String path) {
        return WHITE_LIST.stream().anyMatch(path::startsWith);
    }

    private List<String> getEffectivePermissions(String roleCode) {
        if (SUPER_ADMIN_ROLE.equals(roleCode)) {
            return permissionMapper.selectAllPermissionCodes();
        }
        return permissionMapper.selectPermissionCodesByRoleCode(roleCode);
    }
}
