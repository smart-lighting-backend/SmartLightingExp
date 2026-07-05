package com.experiment.smartlightingexp.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PolicyTestRequest {

    /** 策略条件 JSON（从编辑表单传入） */
    private String conditions;

    /** 策略动作，如 "DIMMING(75)"（从编辑表单传入） */
    private String action;

    /** 策略名称（可选，用于返回结果） */
    private String name;

    // ── 模拟传感器输入 ──

    private BigDecimal illuminance;

    private BigDecimal temperature;

    private BigDecimal humidity;

    private Integer pir;

    private Integer trafficFlow;

    private String currentTime;
}
