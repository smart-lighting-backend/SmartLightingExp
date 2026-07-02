package com.experiment.smartlightingexp.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("device")
public class Device {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String deviceId;

    private String name;

    private String area;

    private String location;

    private Integer status;

    private BigDecimal healthScore;

    private String topicPrefix;

    private LocalDateTime lastHeartbeatAt;

    private Boolean enabled;

    private Boolean deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
