package com.experiment.smartlightingexp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体 — 登录鉴权和操作审计追溯。
 * roleId 关联 role 表，支持 RBAC 权限模型。
 */
@Data
@TableName("user")
@JsonIgnoreProperties({"roleName", "roleCode"})
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String password;

    private String realName;

    private String phone;

    private String email;

    /** 管辖区域编码（如 CQ-01），用于数据权限隔离 */
    private String areaCode;

    private Long roleId;

    private Boolean enabled;

    /** 最后登录 IP 地址 */
    private String lastLoginIp;

    /** 最后登录时间 */
    private LocalDateTime lastLoginTime;

    /** 软删除标记 */
    private Boolean deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
