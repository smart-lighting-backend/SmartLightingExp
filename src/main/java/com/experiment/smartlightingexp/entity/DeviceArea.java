package com.experiment.smartlightingexp.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备分区实体 — 支持树形层级（parent_id）。
 */
@Data
@TableName("device_area")
public class DeviceArea {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 区域名称（如 A区、B区、南门） */
    private String name;

    /** 区域描述 */
    private String description;

    /** 父区域 ID，null 表示顶级区域 */
    @TableField(value = "parent_id", updateStrategy = FieldStrategy.IGNORED)
    private Long parentId;

    /** 是否启用 */
    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
