package com.experiment.smartlightingexp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.entity.AlarmRecord;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.entity.EnergyRecord;
import com.experiment.smartlightingexp.mapper.AlarmRecordMapper;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.mapper.EnergyRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 仪表盘控制器 — 首页统计、能耗趋势、分区数据。
 */
@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DeviceMapper deviceMapper;
    private final AlarmRecordMapper alarmRecordMapper;
    private final EnergyRecordMapper energyRecordMapper;

    /**
     * 仪表盘统计概览。
     * GET /api/dashboard/stats
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        long totalDevices = deviceMapper.selectCount(
                new LambdaQueryWrapper<Device>().eq(Device::getDeleted, false));
        long onlineDevices = deviceMapper.selectCount(
                new LambdaQueryWrapper<Device>().eq(Device::getDeleted, false).eq(Device::getStatus, 1));
        long alertCount = alarmRecordMapper.selectCount(
                new LambdaQueryWrapper<AlarmRecord>().eq(AlarmRecord::getStatus, "ACTIVE"));

        // 在线率
        String onlineRate = totalDevices > 0
                ? BigDecimal.valueOf(onlineDevices * 10000 / totalDevices)
                .divide(BigDecimal.valueOf(100), 1, RoundingMode.HALF_UP).toString()
                : "0.0";

        // 节能率：查询今日 energy_record 的平均 saving_rate
        BigDecimal avgSavingRate = energyRecordMapper.selectList(
                new LambdaQueryWrapper<EnergyRecord>()
                        .eq(EnergyRecord::getRecordDate, LocalDate.now())
                        .last("LIMIT 1000"))
                .stream()
                .map(EnergyRecord::getSavingRate)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 今日总能耗
        BigDecimal todayEnergy = energyRecordMapper.selectList(
                new LambdaQueryWrapper<EnergyRecord>()
                        .eq(EnergyRecord::getRecordDate, LocalDate.now())
                        .last("LIMIT 1000"))
                .stream()
                .map(EnergyRecord::getEstimatedKwh)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalDevices", totalDevices);
        result.put("onlineDevices", onlineDevices);
        result.put("onlineRate", onlineRate);
        result.put("alertCount", alertCount);
        result.put("energySavingRate", avgSavingRate);
        result.put("todayEnergy", todayEnergy);

        log.info("[仪表盘] stats: total={}, online={}, alerts={}, rate={}%", totalDevices, onlineDevices, alertCount, onlineRate);
        return Result.success(result);
    }

    /**
     * 能耗趋势（今日 vs 上周同期）。
     * GET /api/dashboard/energy-trend
     */
    @GetMapping("/energy-trend")
    public Result<Map<String, Object>> energyTrend() {
        // 生成 24 小时标签
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            labels.add(String.format("%02d:00", i));
        }

        // 查询今日和上周同日的能耗数据
        LocalDate today = LocalDate.now();
        LocalDate lastWeek = today.minusDays(7);

        // 按小时聚合今日能耗（模拟：将日总量按典型照明曲线分布）
        List<EnergyRecord> todayRecords = energyRecordMapper.selectList(
                new LambdaQueryWrapper<EnergyRecord>()
                        .eq(EnergyRecord::getRecordDate, today)
                        .last("LIMIT 1000"));
        List<EnergyRecord> lastWeekRecords = energyRecordMapper.selectList(
                new LambdaQueryWrapper<EnergyRecord>()
                        .eq(EnergyRecord::getRecordDate, lastWeek)
                        .last("LIMIT 1000"));

        BigDecimal todayTotal = todayRecords.stream()
                .map(EnergyRecord::getEstimatedKwh)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lastWeekTotal = lastWeekRecords.stream()
                .map(EnergyRecord::getEstimatedKwh)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 路灯典型能耗分布权重（0-23时）
        double[] weights = {
                0.08, 0.07, 0.06, 0.05, 0.04, 0.03, // 0-5时 逐渐下降
                0.02, 0.01, 0.01, 0.01, 0.01, 0.02, // 6-11时 低谷
                0.02, 0.02, 0.01, 0.01, 0.01, 0.02, // 12-17时
                0.03, 0.05, 0.08, 0.10, 0.10, 0.09  // 18-23时 晚高峰
        };

        List<Double> current = new ArrayList<>();
        List<Double> lastWeekData = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            current.add(todayTotal.doubleValue() * weights[i]);
            lastWeekData.add(lastWeekTotal.doubleValue() * weights[i]);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("labels", labels);
        result.put("current", current);
        result.put("lastWeek", lastWeekData);

        return Result.success(result);
    }

    /**
     * 分区设备状态。
     * GET /api/dashboard/districts
     */
    @GetMapping("/districts")
    public Result<List<Map<String, Object>>> districts() {
        // 查询所有未删除设备
        List<Device> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<Device>().eq(Device::getDeleted, false));

        // 按 area 分组统计
        Map<String, List<Device>> grouped = devices.stream()
                .filter(d -> d.getArea() != null && !d.getArea().isBlank())
                .collect(Collectors.groupingBy(Device::getArea));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<Device>> entry : grouped.entrySet()) {
            List<Device> list = entry.getValue();
            long online = list.stream().filter(d -> d.getStatus() != null && d.getStatus() == 1).count();
            long offline = list.stream().filter(d -> d.getStatus() != null && d.getStatus() == 2).count();
            long warning = list.stream().filter(d -> d.getStatus() != null && d.getStatus() == 3).count();

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.getKey());
            item.put("online", online);
            item.put("offline", offline);
            item.put("warning", warning);
            result.add(item);
        }

        // 按区域名称排序
        result.sort(Comparator.comparing(m -> (String) m.get("name")));

        return Result.success(result);
    }
}
