package com.experiment.smartlightingexp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.common.SecurityContext;
import com.experiment.smartlightingexp.dto.DeviceQueryRequest;
import com.experiment.smartlightingexp.entity.*;
import com.experiment.smartlightingexp.mapper.*;
import com.experiment.smartlightingexp.service.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.*;

/**
 * 设备管理控制器 — 设备增删改查 + 组合条件分页查询。
 * 写操作记录审计日志，满足 IR-11 安全可信控制的可追溯要求。
 */
@Slf4j
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;
    private final AuditLogMapper auditLogMapper;

    /**
     * 组合条件分页查询设备列表。
     */
    @PostMapping("/list")
    public Result<IPage<Device>> list(@RequestBody DeviceQueryRequest request) {
        LambdaQueryWrapper<Device> wrapper = new LambdaQueryWrapper<>();

        // 默认只查未删除的设备
        wrapper.eq(Device::getDeleted, false);

        if (request.getDeviceId() != null && !request.getDeviceId().isBlank()) {
            wrapper.eq(Device::getDeviceId, request.getDeviceId());
        }
        if (request.getName() != null && !request.getName().isBlank()) {
            wrapper.like(Device::getName, request.getName());
        }
        if (request.getArea() != null && !request.getArea().isBlank()) {
            wrapper.eq(Device::getArea, request.getArea());
        }
        if (request.getLocation() != null && !request.getLocation().isBlank()) {
            wrapper.like(Device::getLocation, request.getLocation());
        }
        if (request.getStatus() != null) {
            wrapper.eq(Device::getStatus, request.getStatus());
        }
        if (request.getEnabled() != null) {
            wrapper.eq(Device::getEnabled, request.getEnabled());
        }
        if (request.getHealthScoreMin() != null) {
            wrapper.ge(Device::getHealthScore, request.getHealthScoreMin());
        }
        if (request.getHealthScoreMax() != null) {
            wrapper.le(Device::getHealthScore, request.getHealthScoreMax());
        }

        wrapper.orderByDesc(Device::getCreateTime);

        Page<Device> page = new Page<>(request.getPage(), request.getSize());
        IPage<Device> result = deviceService.page(page, wrapper);

        log.info("[设备查询] 条件: deviceId={}, name={}, area={}, status={}, 结果数={}",
                request.getDeviceId(), request.getName(), request.getArea(),
                request.getStatus(), result.getRecords().size());

        return Result.success(result);
    }

    /**
     * 分页查询设备列表（GET 方式，URL 查询参数）。
     * 支持关键词搜索（匹配 deviceId / name / location）+ 区域 / 状态 / 启用状态筛选。
     */
    @GetMapping("/page")
    public Result<IPage<Device>> page(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Boolean enabled) {

        // 参数校验
        if (pageNum < 1) pageNum = 1;
        if (pageSize < 1) pageSize = 10;
        if (pageSize > 100) pageSize = 100;

        LambdaQueryWrapper<Device> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Device::getDeleted, false);

        // 关键词模糊匹配 deviceId / name / location
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w
                    .like(Device::getDeviceId, keyword)
                    .or().like(Device::getName, keyword)
                    .or().like(Device::getLocation, keyword));
        }
        if (area != null && !area.isBlank()) {
            wrapper.eq(Device::getArea, area);
        }
        if (status != null) {
            wrapper.eq(Device::getStatus, status);
        }
        if (enabled != null) {
            wrapper.eq(Device::getEnabled, enabled);
        }

        // 排序：区域升序 → 设备编号升序
        wrapper.orderByAsc(Device::getArea).orderByAsc(Device::getDeviceId);

        Page<Device> page = new Page<>(pageNum, pageSize);
        IPage<Device> result = deviceService.page(page, wrapper);

        log.info("[设备分页] keyword={}, area={}, status={}, enabled={}, 结果数={}",
                keyword, area, status, enabled, result.getRecords().size());
        return Result.success(result);
    }

    /**
     * 查询单个设备详情。
     */
    @GetMapping("/{deviceId}")
    public Result<Device> getByDeviceId(@PathVariable String deviceId) {
        Device device = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, deviceId)
                .eq(Device::getDeleted, false)
                .one();
        if (device == null) {
            return Result.error("设备不存在");
        }
        return Result.success(device);
    }

    /**
     * 新增设备。
     */
    @PostMapping
    public Result<Device> create(@RequestBody Device device,
                                 HttpServletRequest httpRequest) {
        if (device.getDeviceId() == null || device.getDeviceId().isBlank()) {
            return Result.error(400, "设备编号不能为空");
        }

        // 检查 deviceId 唯一性（包含已删除的数据，因为唯一索引仍占用）
        Device exist = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, device.getDeviceId())
                .one();
        if (exist != null) {
            return Result.error(409, "设备编号已存在");
        }

        if (device.getStatus() == null) device.setStatus(1);
        if (device.getEnabled() == null) device.setEnabled(true);
        if (device.getHealthScore() == null) device.setHealthScore(new BigDecimal("100.00"));
        if (device.getTopicPrefix() == null || device.getTopicPrefix().isBlank()) device.setTopicPrefix("streetlight");
        device.setDeleted(false);
        deviceService.save(device);

        saveAuditLog("DEVICE_CREATE", "DEVICE", device.getDeviceId(),
                "新增设备-" + device.getName(), "SUCCESS", httpRequest);
        log.info("[设备] 新增: deviceId={}, name={}", device.getDeviceId(), device.getName());
        return Result.success(device);
    }

    /**
     * 更新设备信息。
     */
    @PutMapping("/{deviceId}")
    public Result<Device> update(@PathVariable String deviceId,
                                 @RequestBody Device device,
                                 HttpServletRequest httpRequest) {
        Device existing = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, deviceId)
                .eq(Device::getDeleted, false)
                .one();
        if (existing == null) {
            saveAuditLog("DEVICE_UPDATE", "DEVICE", deviceId,
                    "设备不存在-更新失败", "FAIL", httpRequest);
            return Result.error(404, "设备不存在");
        }

        // 只更新允许修改的字段
        if (device.getName() != null) existing.setName(device.getName());
        if (device.getArea() != null) existing.setArea(device.getArea());
        if (device.getLocation() != null) existing.setLocation(device.getLocation());
        if (device.getStatus() != null) existing.setStatus(device.getStatus());
        if (device.getHealthScore() != null) existing.setHealthScore(device.getHealthScore());
        if (device.getTopicPrefix() != null) {
            existing.setTopicPrefix(device.getTopicPrefix().isBlank() ? "streetlight" : device.getTopicPrefix());
        }
        if (device.getEnabled() != null) existing.setEnabled(device.getEnabled());
        deviceService.updateById(existing);

        // 重新查询返回最新数据
        Device updated = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, deviceId)
                .eq(Device::getDeleted, false)
                .one();

        saveAuditLog("DEVICE_UPDATE", "DEVICE", deviceId,
                "更新设备-" + existing.getName(), "SUCCESS", httpRequest);
        log.info("[设备] 更新: deviceId={}, name={}", deviceId, existing.getName());
        return Result.success(updated);
    }

    /**
     * 删除设备（软删除）。
     */
    @DeleteMapping("/{deviceId}")
    public Result<Void> delete(@PathVariable String deviceId,
                               HttpServletRequest httpRequest) {
        Device existing = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, deviceId)
                .eq(Device::getDeleted, false)
                .one();
        if (existing == null) {
            saveAuditLog("DEVICE_DELETE", "DEVICE", deviceId,
                    "设备不存在-删除失败", "FAIL", httpRequest);
            return Result.error("设备不存在");
        }

        existing.setDeleted(true);
        existing.setEnabled(false);
        existing.setStatus(0);
        deviceService.updateById(existing);

        saveAuditLog("DEVICE_DELETE", "DEVICE", deviceId,
                "删除设备-" + existing.getName(), "SUCCESS", httpRequest);
        log.info("[设备] 删除: deviceId={}, name={}", deviceId, existing.getName());
        return Result.success();
    }

    // ======================== 审计日志 ========================

    private void saveAuditLog(String action, String targetType, String targetId,
                              String detail, String result, HttpServletRequest request) {
        try {
            AuditLog logEntry = new AuditLog();
            String operator = SecurityContext.getCurrentUsername();
            logEntry.setOperator(operator != null ? operator : "UNKNOWN");
            logEntry.setAction(action);
            logEntry.setTargetType(targetType);
            logEntry.setTargetId(targetId);
            logEntry.setDetail(detail);
            logEntry.setResult(result);
            logEntry.setIpAddress(getClientIp(request));
            logEntry.setOperatedAt(LocalDateTime.now());
            auditLogMapper.insert(logEntry);
        } catch (Exception e) {
            log.error("审计日志记录失败: {}", e.getMessage());
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    // ───────────── 健康评分 API ─────────────

    private final AlarmRecordMapper alarmRecordMapper;
    private final TelemetryMapper telemetryMapper;
    private final ControlCommandMapper controlCommandMapper;
    private final VisionEventService visionEventService;
    private final VoiceEventService voiceEventService;
    private final AlarmRecordService alarmRecordService;
    private final ObjectMapper objectMapper;

    @GetMapping("/{deviceId}/health")
    public Result<Map<String, Object>> getHealth(@PathVariable String deviceId) {
        Device device = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, deviceId).eq(Device::getDeleted, false).one();
        if (device == null) return Result.error("设备不存在");

        // 健康总分使用已存储的 health_score，与设备列表/详情页保持一致
        int overallScore = device.getHealthScore() != null ? device.getHealthScore().intValue() : 0;

        // 四个维度仍实时计算，展示诊断细节（权重与 HealthScoreTask 一致）
        int offline = calcOffline(deviceId);
        int comm = calcComm(deviceId);
        int response = calcResponse(deviceId);
        int sensor = calcSensor(device);

        String level;
        String color;
        if (overallScore >= 90) { level = "优秀"; color = "#4caf50"; }
        else if (overallScore >= 70) { level = "良好"; color = "#ff9800"; }
        else if (overallScore >= 50) { level = "一般"; color = "#ff9800"; }
        else if (overallScore >= 30) { level = "较差"; color = "#f44336"; }
        else { level = "危险"; color = "#f44336"; }

        List<Map<String, Object>> dimensions = new ArrayList<>();
        dimensions.add(dimItem("离线频次", offline, "30%", offline == 100 ? null : "近7天离线次数较多"));
        dimensions.add(dimItem("通信质量", comm, "25%", comm == 100 ? null : "遥测上报间隔波动较大"));
        dimensions.add(dimItem("指令响应率", response, "25%", response == 100 ? null : "部分指令未收到设备确认"));
        dimensions.add(dimItem("传感器状态", sensor, "20%", sensor == 100 ? null : "部分传感器读数异常或为空"));

        String suggestion = overallScore >= 90 ? "设备状态极佳" :
                overallScore >= 70 ? "设备总体健康，建议定期巡检" :
                overallScore >= 50 ? "关注设备运行状况，建议安排检查" :
                "设备健康度较低，建议尽快安排维修";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("deviceName", device.getName());
        result.put("overallScore", overallScore);
        result.put("level", level);
        result.put("levelColor", color);
        result.put("dimensions", dimensions);
        result.put("suggestion", suggestion);
        result.put("evaluatedAt", LocalDateTime.now().toString());
        return Result.success(result);
    }

    @GetMapping("/health/summary")
    public Result<Map<String, Object>> healthSummary() {
        List<Device> devices = deviceService.lambdaQuery()
                .eq(Device::getEnabled, true).eq(Device::getDeleted, false).list();
        List<Map<String, Object>> list = new ArrayList<>();
        int healthy = 0, warning = 0, critical = 0;
        double sum = 0;
        for (Device d : devices) {
            int s = d.getHealthScore() != null ? d.getHealthScore().intValue() : 0;
            sum += s;
            if (s >= 90) healthy++;
            else if (s >= 70) healthy++;
            else if (s >= 50) warning++;
            else critical++;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceId", d.getDeviceId());
            item.put("name", d.getName());
            item.put("score", s);
            item.put("level", s >= 90 ? "优秀" : s >= 70 ? "良好" : s >= 50 ? "一般" : s >= 30 ? "较差" : "危险");
            list.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalDevices", devices.size());
        result.put("healthyCount", healthy);
        result.put("warningCount", warning);
        result.put("criticalCount", critical);
        result.put("averageScore", devices.isEmpty() ? 0 : Math.round(sum / devices.size()));
        result.put("list", list);
        return Result.success(result);
    }

    private Map<String, Object> dimItem(String name, int score, String weight, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("score", score);
        m.put("weight", weight);
        m.put("reason", reason);
        return m;
    }

    private int calcOffline(String deviceId) {
        long count = alarmRecordMapper.selectCount(
                new LambdaQueryWrapper<AlarmRecord>()
                        .eq(AlarmRecord::getDeviceId, deviceId)
                        .eq(AlarmRecord::getType, "OFFLINE")
                        .ge(AlarmRecord::getStartAt, LocalDateTime.now().minusDays(7)));
        if (count == 0) return 100;
        if (count == 1) return 80;
        if (count == 2) return 60;
        if (count == 3) return 40;
        return 20;
    }

    private int calcComm(String deviceId) {
        List<Telemetry> list = telemetryMapper.selectList(
                new LambdaQueryWrapper<Telemetry>()
                        .eq(Telemetry::getDeviceId, deviceId)
                        .ge(Telemetry::getCreateTime, LocalDateTime.now().minusHours(24))
                        .orderByAsc(Telemetry::getCreateTime));
        if (list.size() < 3) return 0;
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < list.size(); i++) {
            Duration d = Duration.between(list.get(i - 1).getCreateTime(), list.get(i).getCreateTime());
            gaps.add((double) Math.abs(d.getSeconds() - 300));
        }
        double avg = gaps.stream().mapToDouble(Double::doubleValue).average().orElse(999);
        if (avg < 30) return 100;
        if (avg < 60) return 80;
        if (avg < 120) return 60;
        return 40;
    }

    private int calcResponse(String deviceId) {
        List<ControlCommand> all = controlCommandMapper.selectList(
                new LambdaQueryWrapper<ControlCommand>()
                        .eq(ControlCommand::getDeviceId, deviceId)
                        .ge(ControlCommand::getIssuedAt, LocalDateTime.now().minusDays(7)));
        if (all.isEmpty()) return 100;
        long acked = all.stream().filter(c -> c.getAckAt() != null).count();
        double rate = (double) acked / all.size();
        if (rate >= 1.0) return 100;
        if (rate >= 0.8) return 80;
        if (rate >= 0.5) return 60;
        return 30;
    }

    private int calcSensor(Device device) {
        if (device.getLatestData() == null || device.getLatestData().isBlank()) return 0;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(device.getLatestData(), Map.class);
            int abnormal = 0, total = 0;
            abnormal += chk(data, "illuminance", 0, 2000); total++;
            abnormal += chk(data, "temperature", -10, 50); total++;
            abnormal += chk(data, "humidity", 0, 100);     total++;
            abnormal += chk(data, "pm25", 0, 500);         total++;
            abnormal += chk(data, "aqi", 0, 500);          total++;
            if (total == 0) return 100;
            double ratio = (double) abnormal / total;
            if (ratio == 0) return 100;
            if (ratio <= 0.2) return 70;
            if (ratio <= 0.5) return 40;
            return 10;
        } catch (Exception e) { return 0; }
    }

    private int chk(Map<String, Object> data, String key, double min, double max) {
        Object val = data.get(key);
        if (val == null) return 1;
        try {
            double d = Double.parseDouble(val.toString());
            return (d >= min && d <= max) ? 0 : 1;
        } catch (NumberFormatException e) { return 1; }
    }

    /** 融合感知面板：聚合遥测 + 视觉 + 语音 + 告警 + 健康分 */
    @GetMapping("/{deviceId}/perception")
    public Result<Map<String, Object>> perception(@PathVariable String deviceId) {
        Device device = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, deviceId).eq(Device::getDeleted, false).one();
        if (device == null) return Result.error("设备不存在");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", device.getDeviceId());
        result.put("deviceName", device.getName());
        result.put("status", device.getStatus());
        result.put("lastHeartbeatAt", device.getLastHeartbeatAt() != null ? device.getLastHeartbeatAt().toString() : null);
        result.put("healthScore", device.getHealthScore());

        // 遥测快照
        if (device.getLatestData() != null && !device.getLatestData().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> telemetry = objectMapper.readValue(device.getLatestData(), Map.class);
                result.put("telemetry", telemetry);
            } catch (JsonProcessingException e) {
                result.put("telemetry", null);
            }
        } else {
            result.put("telemetry", null);
        }

        // 最新视觉事件
        VisionEvent ve = visionEventService.getOne(
                new LambdaQueryWrapper<VisionEvent>()
                        .eq(VisionEvent::getDeviceId, deviceId)
                        .orderByDesc(VisionEvent::getOccurredAt).last("LIMIT 1"));
        if (ve != null) {
            Map<String, Object> veMap = new LinkedHashMap<>();
            veMap.put("eventType", ve.getEventType());
            veMap.put("confidence", ve.getConfidence());
            veMap.put("snapshotRef", ve.getSnapshotRef());
            veMap.put("occurredAt", ve.getOccurredAt() != null ? ve.getOccurredAt().toString() : null);
            result.put("latestVision", veMap);
        } else {
            result.put("latestVision", null);
        }

        // 最新语音事件
        VoiceEvent vo = voiceEventService.getOne(
                new LambdaQueryWrapper<VoiceEvent>()
                        .eq(VoiceEvent::getDeviceId, deviceId)
                        .orderByDesc(VoiceEvent::getOccurredAt).last("LIMIT 1"));
        if (vo != null) {
            Map<String, Object> voMap = new LinkedHashMap<>();
            voMap.put("type", vo.getType());
            voMap.put("content", vo.getContent());
            voMap.put("source", vo.getSource());
            voMap.put("occurredAt", vo.getOccurredAt() != null ? vo.getOccurredAt().toString() : null);
            result.put("latestVoice", voMap);
        } else {
            result.put("latestVoice", null);
        }

        // 最近告警
        List<AlarmRecord> alarms = alarmRecordService.list(
                new LambdaQueryWrapper<AlarmRecord>()
                        .eq(AlarmRecord::getDeviceId, deviceId)
                        .orderByDesc(AlarmRecord::getStartAt).last("LIMIT 3"));
        List<Map<String, Object>> alarmList = new ArrayList<>();
        for (AlarmRecord a : alarms) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("type", a.getType());
            am.put("level", a.getLevel());
            am.put("startAt", a.getStartAt() != null ? a.getStartAt().toString() : null);
            am.put("recoverAt", a.getRecoverAt() != null ? a.getRecoverAt().toString() : null);
            alarmList.add(am);
        }
        result.put("recentAlarms", alarmList);

        return Result.success(result);
    }
}
