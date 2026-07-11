package com.experiment.smartlightingexp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 登录响应结果。
 */
@Data
@AllArgsConstructor
public class LoginResponse {

    private String token;
    private String username;
    private String realName;
    private String department;
    private String phone;
    private String roleCode;

    /** 角色中文名（如"路灯管理员"），供前端直接展示 */
    private String roleName;

    /** 当前用户的权限编码列表（前端按钮级控制） */
    private List<String> permissions;

    /** 当前用户可见的菜单树（前端动态导航） */
    private List<MenuTreeNode> menus;
}
