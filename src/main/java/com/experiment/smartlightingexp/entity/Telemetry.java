package com.experiment.smartlightingexp.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("telemetry")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Telemetry {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 设备编号 */
    private String deviceId;

    /** 光照强度（lux） */
    private BigDecimal illuminance;

    /** 温度（℃） */
    private BigDecimal temperature;

    /** 湿度（%） */
    private BigDecimal humidity;

    /** PM2.5 浓度（μg/m³） */
    private BigDecimal pm25;

    /** 空气质量指数 */
    private Integer aqi;

    /** 人体红外检测 0-无人 1-有人 */
    private Integer pir;

    /** 车流量（辆/分钟） */
    private Integer trafficFlow;

    private LocalDateTime collectedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
