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
@TableName("telemetry")
public class Telemetry {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String deviceId;

    private BigDecimal illuminance;

    private BigDecimal temperature;

    private BigDecimal humidity;

    private BigDecimal pm25;

    private Integer aqi;

    private Integer pir;

    private Integer trafficFlow;

    private LocalDateTime collectedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
