package com.experiment.smartlightingexp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限实体 — 细粒度操作权限，与角色多对多关联。
 */
@Data
@TableName("permission")
public class Permission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String permissionCode;

    private String description;

    private LocalDateTime createTime;
}
