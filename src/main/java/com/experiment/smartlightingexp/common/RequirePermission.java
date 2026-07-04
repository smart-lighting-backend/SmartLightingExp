package com.experiment.smartlightingexp.common;

import java.lang.annotation.*;

/**
 * 权限校验注解 — 标注在 Controller 方法上，指定该接口所需的权限编码。
 * 若当前用户 JWT 中的 permissions 不包含该编码，返回 403。
 *
 * <pre>{@code
 * @RequirePermission("device:create")
 * @PostMapping
 * public Result<Void> create() { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {

    /** 所需的权限编码（如 "device:create", "alarm:handle"） */
    String value();
}
