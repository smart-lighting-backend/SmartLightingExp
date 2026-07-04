package com.experiment.smartlightingexp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限实体 — 支持树形层级（模块级 MODULE / 操作级 ACTION）。
 */
@Data
@TableName("permission")
public class Permission {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父权限ID（NULL=模块级，非NULL=操作级） */
    private Long parentId;

    private String name;

    private String permissionCode;

    private String description;

    /** 权限类型：MODULE-模块, ACTION-操作 */
    private String type;

    private LocalDateTime createTime;
}
