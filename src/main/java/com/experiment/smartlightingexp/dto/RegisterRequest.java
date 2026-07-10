package com.experiment.smartlightingexp.dto;

import lombok.Data;

/**
 * 注册请求参数 DTO。
 */
@Data
public class RegisterRequest {

    /** 用户名（必填，只能包含字母、数字和下划线） */
    private String username;

    /** 密码（必填，至少 8 位） */
    private String password;

    /** 真实姓名（可选） */
    private String realName;

    /** 手机号（可选） */
    private String phone;

    /** 角色 ID（必填，不可为 SUPER_ADMIN） */
    private Long roleId;
}
