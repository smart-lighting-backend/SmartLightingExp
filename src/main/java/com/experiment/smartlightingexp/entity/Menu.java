package com.experiment.smartlightingexp.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 动态菜单实体 — 支持树形层级，前端据此渲染导航菜单。
 */
@Data
@TableName("menu")
public class Menu {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父菜单ID（NULL=顶级菜单） */
    @TableField(value = "parent_id", updateStrategy = FieldStrategy.IGNORED)
    private Long parentId;

    /** 菜单名称 */
    private String name;

    /** 关联权限编码（NULL=无需权限，全员可见） */
    private String permissionCode;

    /** 图标名称 */
    private String icon;

    /** 前端路由路径 */
    private String path;

    /** 前端组件路径 */
    private String component;

    /** 排序号 */
    private Integer sort;

    /** 是否启用 */
    private Boolean enabled;

    private LocalDateTime createTime;
}
