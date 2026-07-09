package com.experiment.smartlightingexp.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量设备分区分配请求体。
 * <p>
 * 两种传参方式互斥择一：
 * <ul>
 *   <li>{@code deviceIds} —— 数据库主键（前端沿用）</li>
 *   <li>{@code deviceCodes} —— deviceId 字符串（移动端使用）</li>
 * </ul>
 */
@Data
public class BatchAreaRequest {

    /** 要分配的设备数据库主键列表（前端使用） */
    private List<Long> deviceIds;

    /** 要分配的设备编号列表，如 ["SL-001","SL-002"]（移动端使用） */
    private List<String> deviceCodes;

    /** 目标区域 ID（传 null 则清除区域） */
    private Long areaId;
}
