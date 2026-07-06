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

    /** 区域名称（冗余展示字段，由 area_id 自动填充） */
    private String area;

    /** 关联 device_area.id */
    private Long areaId;

    private String location;

    private Integer status;

    private BigDecimal healthScore;

    private String topicPrefix;

    private LocalDateTime lastHeartbeatAt;

    private String latestData;

    private LocalDateTime lastManualAt;

    /** 额定功率（W），用于能耗估算 */
    private BigDecimal ratedPower;

    /** 是否处于手动控制模式 */
    private Boolean manualMode;

    /** 手动模式过期时间 */
    private LocalDateTime manualExpireAt;

    private Boolean enabled;

    private Boolean deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
