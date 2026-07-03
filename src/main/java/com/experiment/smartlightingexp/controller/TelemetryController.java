package com.experiment.smartlightingexp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.dto.TelemetryQueryRequest;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.entity.Telemetry;
import com.experiment.smartlightingexp.service.DeviceService;
import com.experiment.smartlightingexp.service.TelemetryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 遥测数据控制器 — 查询最新遥测（含光照）和历史遥测数据。
 * 数据流：MQTT 接收 → Telemetry 入库 → device.latestData 快照。
 */
@Slf4j
@RestController
@RequestMapping("/api/telemetry")
@RequiredArgsConstructor
public class TelemetryController {

    private final TelemetryService telemetryService;
    private final DeviceService deviceService;
    private final ObjectMapper objectMapper;

    /**
     * 查询设备最新遥测数据（含光照强度）。
     * 数据来源：device.latestData JSON 快照字段。
     */
    @GetMapping("/latest/{deviceId}")
    public Result<Map<String, Object>> getLatest(@PathVariable String deviceId) {
        Device device = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, deviceId)
                .eq(Device::getDeleted, false)
                .one();

        if (device == null) {
            return Result.error("设备不存在");
        }

        if (device.getLatestData() == null || device.getLatestData().isBlank()) {
            return Result.error("该设备暂无遥测数据");
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(device.getLatestData(), Map.class);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deviceId", device.getDeviceId());
            result.put("name", device.getName());
            result.put("area", device.getArea());
            result.put("status", device.getStatus());
            result.put("healthScore", device.getHealthScore());
            result.put("data", data);
            result.put("lastHeartbeatAt", device.getLastHeartbeatAt());

            return Result.success(result);
        } catch (JsonProcessingException e) {
            log.error("解析 latestData JSON 失败: deviceId={}, raw={}", deviceId, device.getLatestData(), e);
            return Result.error("遥测数据格式异常");
        }
    }

    /**
     * 查询遥测历史数据（分页）。
     * 支持按 deviceId、采集时间范围筛选。
     */
    @PostMapping("/history")
    public Result<IPage<Telemetry>> getHistory(@RequestBody TelemetryQueryRequest request) {
        LambdaQueryWrapper<Telemetry> wrapper = new LambdaQueryWrapper<>();

        if (request.getDeviceId() != null && !request.getDeviceId().isBlank()) {
            wrapper.eq(Telemetry::getDeviceId, request.getDeviceId());
        }
        if (request.getCollectedAtFrom() != null) {
            wrapper.ge(Telemetry::getCollectedAt, request.getCollectedAtFrom());
        }
        if (request.getCollectedAtTo() != null) {
            wrapper.le(Telemetry::getCollectedAt, request.getCollectedAtTo());
        }

        wrapper.orderByDesc(Telemetry::getCollectedAt);

        Page<Telemetry> page = new Page<>(request.getPage(), request.getSize());
        IPage<Telemetry> result = telemetryService.page(page, wrapper);

        log.info("[遥测历史] 条件: deviceId={}, 结果数={}",
                request.getDeviceId(), result.getRecords().size());
        return Result.success(result);
    }
}
