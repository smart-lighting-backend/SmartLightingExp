package com.experiment.smartlightingexp.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lighting_policy")
public class LightingPolicy {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String policyType;

    private String conditions;

    private String action;

    private Integer priority;

    private Boolean enabled;

    private Boolean deleted;

    private String effectiveTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
