package com.experiment.smartlightingexp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户实体 — 登录鉴权和操作审计追溯。
 * roleId 关联 role 表，支持 RBAC 权限模型。
 */
@Data
@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String password;

    private String realName;

    private Long roleId;

    private Boolean enabled;

    private java.time.LocalDateTime createTime;

    private java.time.LocalDateTime updateTime;
}
