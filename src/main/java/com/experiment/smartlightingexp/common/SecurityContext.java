package com.experiment.smartlightingexp.common;

import java.util.List;

/**
 * 安全上下文 — 通过 ThreadLocal 存储当前登录用户信息，
 * 供 Controller / Service 层在请求链路中随时获取操作人和权限信息。
 */
public class SecurityContext {

    private static final ThreadLocal<UserInfo> CONTEXT = new ThreadLocal<>();

    /**
     * 设置当前用户信息。
     */
    public static void setCurrentUser(UserInfo user) {
        CONTEXT.set(user);
    }

    /**
     * 获取当前用户名。
     */
    public static String getCurrentUsername() {
        UserInfo user = CONTEXT.get();
        return user != null ? user.username() : null;
    }

    /**
     * 获取当前用户角色编码。
     */
    public static String getCurrentRole() {
        UserInfo user = CONTEXT.get();
        return user != null ? user.roleCode() : null;
    }

    /**
     * 获取当前用户权限编码列表。
     */
    public static List<String> getCurrentPermissions() {
        UserInfo user = CONTEXT.get();
        return user != null ? user.permissions() : List.of();
    }

    /**
     * 判断当前用户是否拥有指定权限。
     */
    public static boolean hasPermission(String permissionCode) {
        UserInfo user = CONTEXT.get();
        return user != null && user.permissions() != null
                && user.permissions().contains(permissionCode);
    }

    /**
     * 获取当前用户完整信息。
     */
    public static UserInfo getCurrentUser() {
        return CONTEXT.get();
    }

    /**
     * 请求处理完毕后清除上下文（在拦截器 afterCompletion 中调用）。
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * 当前登录用户的信息载体。
     *
     * @param username    用户名
     * @param roleCode    角色编码（如 ADMIN / OPERATOR / VIEWER）
     * @param permissions 权限编码列表
     */
    public record UserInfo(String username, String roleCode, List<String> permissions) {
    }
}
