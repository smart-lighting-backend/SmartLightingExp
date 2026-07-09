package com.experiment.smartlightingexp.controller;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.experiment.smartlightingexp.common.RequirePermission;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.common.SecurityContext;
import com.experiment.smartlightingexp.dto.BatchDeviceRequest;
import com.experiment.smartlightingexp.dto.ControlRequest;
import com.experiment.smartlightingexp.entity.AuditLog;
import com.experiment.smartlightingexp.entity.ControlCommand;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.mapper.ControlCommandMapper;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.config.MqttProperties;
import com.experiment.smartlightingexp.mqtt.MqttPublisher;
import com.experiment.smartlightingexp.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 手动控制控制器 — 开灯、关灯、调光。
 * 记录操作人（从 SecurityContext 获取），满足 IR-11 审计追溯要求。
 */
@Slf4j
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class ControlController {

    private final DeviceMapper deviceMapper;
    private final ControlCommandMapper controlCommandMapper;
    private final MqttPublisher mqttPublisher;
    private final MqttProperties mqttProperties;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    @Value("${manual.lock-duration-minutes:30}")
    private int manualLockDurationMinutes;

    private static final List<String> VALID_ACTIONS = List.of("ON", "OFF", "DIMMING");

    /**
     * 手动控制设备（开/关/调光）。
     * 记录手动操作时间 → AI 策略引擎在 30 分钟内跳过此设备。
     */
    @RequirePermission("device:control")
    @PostMapping("/{deviceId}/control")
    public Result<Void> control(@PathVariable String deviceId,
                                @Valid @RequestBody ControlRequest request,
                                HttpServletRequest httpRequest) {
        Device device = deviceMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Device>()
                        .eq(Device::getDeviceId, deviceId));
        if (device == null) {
            saveAuditLog("CONTROL", "DEVICE", deviceId, "设备不存在-操作失败", "FAIL", httpRequest);
            return Result.error("设备不存在");
        }
        if (!VALID_ACTIONS.contains(request.getAction())) {
            saveAuditLog("CONTROL", "DEVICE", deviceId, "无效指令-" + request.getAction(), "FAIL", httpRequest);
            return Result.error("无效指令类型，支持: ON/OFF/DIMMING");
        }
        if ("DIMMING".equals(request.getAction()) && request.getBrightness() == null) {
            saveAuditLog("CONTROL", "DEVICE", deviceId, "DIMMING缺少brightness", "FAIL", httpRequest);
            return Result.error("DIMMING 指令需提供 brightness 参数");
        }

        // 从 SecurityContext 获取当前操作人
        String operator = SecurityContext.getCurrentUsername();
        if (operator == null) {
            operator = "UNKNOWN";
        }

        // 1. 组装指令标识（如 ON / OFF / DIMMING(70)）
        String cmdStr = "DIMMING".equals(request.getAction())
                ? "DIMMING(" + request.getBrightness() + ")"
                : request.getAction();
        LocalDateTime now = LocalDateTime.now();

        // 2. 发布 MQTT 控制指令
        Map<String, Object> cmdPayload = new HashMap<>();
        cmdPayload.put("action", cmdStr);
        cmdPayload.put("issuedAt", now.toString());
        cmdPayload.put("source", "MANUAL");
        cmdPayload.put("operator", operator);
        try {
            mqttPublisher.publish(
                    mqttProperties.getTopicPrefix() + "/" + deviceId + "/command",
                    objectMapper.writeValueAsString(cmdPayload),
                    1);
        } catch (Exception e) {
            log.error("[{}] MQTT publish failed: {}", deviceId, e.getMessage());
            saveAuditLog("CONTROL", "DEVICE", deviceId, "MQTT下发失败-" + e.getMessage(), "FAIL", httpRequest);
            return Result.error("MQTT 下发失败");
        }

        // 3. 记录 control_command 流水（含 operator 字段 → 满足 IR-11 审计追溯）
        ControlCommand cmd = new ControlCommand();
        cmd.setDeviceId(deviceId);
        cmd.setAction(cmdStr);
        cmd.setBrightness("DIMMING".equals(request.getAction()) ? request.getBrightness() : null);
        cmd.setSource("MANUAL");
        cmd.setOperator(operator);
        cmd.setStatus("SENT");
        cmd.setIssuedAt(now);
        cmd.setResultDetail("手动控制-" + cmdStr);
        controlCommandMapper.insert(cmd);

        // 4. 更新设备 — 手动模式 + 锁定时间（不覆盖 latestData，保留遥测快照）
        deviceMapper.update(null,
                new LambdaUpdateWrapper<Device>()
                        .eq(Device::getDeviceId, deviceId)
                        .set(Device::getLatestData, buildManualControlLatestData(device, request, cmdStr, now))
                        .set(Device::getLastManualAt, now)
                        .set(Device::getManualMode, true)
                        .set(Device::getManualExpireAt, now.plusMinutes(manualLockDurationMinutes)));

        // 5. 审计日志
        saveAuditLog("CONTROL", "DEVICE", deviceId, "手动控制-" + cmdStr, "SUCCESS", httpRequest);

        log.info("[{}] Manual control by {} → {} (lastManualAt=now)", deviceId, operator, cmdStr);
        return Result.success();
    }

    /**
     * 批量开灯。
     */
    @RequirePermission("device:control")
    @PutMapping("/batch-turn-on")
    public Result<Map<String, Object>> batchTurnOn(@RequestBody @Valid BatchDeviceRequest request,
                                                   HttpServletRequest httpRequest) {
        return batchControl(request, "ON", httpRequest);
    }

    /**
     * 批量关灯。
     */
    @RequirePermission("device:control")
    @PutMapping("/batch-turn-off")
    public Result<Map<String, Object>> batchTurnOff(@RequestBody @Valid BatchDeviceRequest request,
                                                    HttpServletRequest httpRequest) {
        return batchControl(request, "OFF", httpRequest);
    }

    /**
     * 查询设备控制历史（分页）。
     * 复用 control_command 表，按下发时间倒序排列。
     */
    @RequirePermission("device:read")
    @GetMapping("/{deviceId}/control-history")
    public Result<Page<ControlCommand>> controlHistory(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<ControlCommand> p = controlCommandMapper.selectPage(
                new Page<>(page, size),
                Wrappers.<ControlCommand>lambdaQuery()
                        .eq(ControlCommand::getDeviceId, deviceId)
                        .orderByDesc(ControlCommand::getIssuedAt));
        return Result.success(p);
    }

    /**
     * 解除设备手动锁定，恢复自动控制。
     * DELETE /api/devices/{deviceId}/manual-lock
     */
    @RequirePermission("device:control")
    @DeleteMapping("/{deviceId}/manual-lock")
    public Result<Void> unlockDevice(@PathVariable String deviceId,
                                     HttpServletRequest httpRequest) {
        Device device = deviceMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Device>()
                        .eq(Device::getDeviceId, deviceId));
        if (device == null) {
            return Result.error("设备不存在");
        }
        deviceMapper.update(null,
                new LambdaUpdateWrapper<Device>()
                        .eq(Device::getDeviceId, deviceId)
                        .set(Device::getManualMode, false)
                        .set(Device::getManualExpireAt, null));
        saveAuditLog("UNLOCK", "DEVICE", deviceId, "解除手动锁定-恢复自动控制", "SUCCESS", httpRequest);
        log.info("[{}] Manual lock released, AI control resumed", deviceId);
        return Result.success();
    }

    private String buildManualControlLatestData(Device device, ControlRequest request,
                                                String cmdStr, LocalDateTime issuedAt) {
        Map<String, Object> latestData = new HashMap<>();
        if (device.getLatestData() != null && !device.getLatestData().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(device.getLatestData(), Map.class);
                latestData.putAll(parsed);
            } catch (Exception e) {
                log.warn("[{}] Failed to parse latestData before manual control: {}",
                        device.getDeviceId(), e.getMessage());
            }
        }
        latestData.put("action", cmdStr);
        latestData.put("brightness", resolveManualBrightness(request, latestData));
        latestData.put("controlSource", "MANUAL");
        latestData.put("controlIssuedAt", issuedAt.toString());
        try {
            return objectMapper.writeValueAsString(latestData);
        } catch (Exception e) {
            log.warn("[{}] Failed to write latestData after manual control: {}",
                    device.getDeviceId(), e.getMessage());
            return device.getLatestData();
        }
    }

    private Integer resolveManualBrightness(ControlRequest request, Map<String, Object> latestData) {
        if ("OFF".equals(request.getAction())) return 0;
        if ("DIMMING".equals(request.getAction())) return request.getBrightness();
        Object current = latestData.get("brightness");
        if (current instanceof Number number) return Math.max(1, Math.min(100, number.intValue()));
        if (current != null) {
            try {
                return Math.max(1, Math.min(100, Integer.parseInt(current.toString())));
            } catch (NumberFormatException ignored) {
            }
        }
        return 100;
    }
    /**
     * 批量控制设备灯光。
     */
    private Result<Map<String, Object>> batchControl(BatchDeviceRequest request,
                                                     String action,
                                                     HttpServletRequest httpRequest) {
        List<Long> ids = normalizeDeviceIds(request.getDeviceIds());
        if (ids.isEmpty()) {
            return Result.error(400, "设备ID列表不能为空");
        }

        List<Device> devices = deviceMapper.selectList(
                Wrappers.<Device>lambdaQuery()
                        .in(Device::getId, ids)
                        .eq(Device::getDeleted, false));
        if (devices.isEmpty()) {
            saveAuditLog(batchControlAuditAction(action), "DEVICE", ids.toString(),
                    "未找到可控制设备-批量" + batchControlActionName(action) + "失败", "FAIL", httpRequest);
            return Result.error(404, "未找到可控制设备");
        }

        Set<Long> foundIds = devices.stream().map(Device::getId).collect(Collectors.toSet());
        List<Map<String, Object>> failedDetails = new ArrayList<>();
        for (Long id : ids) {
            if (!foundIds.contains(id)) {
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("id", id);
                failed.put("reason", "设备不存在或已删除");
                failedDetails.add(failed);
            }
        }

        String operator = SecurityContext.getCurrentUsername();
        if (operator == null) {
            operator = "UNKNOWN";
        }

        int success = 0;
        for (Device device : devices) {
            ControlRequest controlRequest = new ControlRequest();
            controlRequest.setAction(action);
            try {
                issueManualControl(device, controlRequest, operator, LocalDateTime.now());
                success++;
            } catch (Exception e) {
                log.error("[{}] Batch manual control {} failed: {}", device.getDeviceId(), action, e.getMessage());
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("id", device.getId());
                failed.put("deviceId", device.getDeviceId());
                failed.put("reason", e.getMessage());
                failedDetails.add(failed);
            }
        }

        if (success == 0) {
            saveAuditLog(batchControlAuditAction(action), "DEVICE", ids.toString(),
                    "批量" + batchControlActionName(action) + "失败", "FAIL", httpRequest);
            return Result.error(500, "批量" + batchControlActionName(action) + "失败");
        }

        saveAuditLog(batchControlAuditAction(action), "DEVICE", ids.toString(),
                "批量" + batchControlActionName(action) + "-" + success + "台", "SUCCESS", httpRequest);
        log.info("[设备] 批量{}: 请求数={}, 成功={}, 失败={}",
                batchControlActionName(action), ids.size(), success, ids.size() - success);
        return Result.success(batchControlResult(ids.size(), success, failedDetails));
    }

    private void issueManualControl(Device device, ControlRequest request,
                                    String operator, LocalDateTime now) throws Exception {
        String cmdStr = "DIMMING".equals(request.getAction())
                ? "DIMMING(" + request.getBrightness() + ")"
                : request.getAction();

        Map<String, Object> cmdPayload = new HashMap<>();
        cmdPayload.put("action", cmdStr);
        cmdPayload.put("issuedAt", now.toString());
        cmdPayload.put("source", "MANUAL");
        cmdPayload.put("operator", operator);
        mqttPublisher.publish(
                mqttProperties.getTopicPrefix() + "/" + device.getDeviceId() + "/command",
                objectMapper.writeValueAsString(cmdPayload),
                1);

        ControlCommand cmd = new ControlCommand();
        cmd.setDeviceId(device.getDeviceId());
        cmd.setAction(cmdStr);
        cmd.setBrightness("DIMMING".equals(request.getAction()) ? request.getBrightness() : null);
        cmd.setSource("MANUAL");
        cmd.setOperator(operator);
        cmd.setStatus("SENT");
        cmd.setIssuedAt(now);
        cmd.setResultDetail("手动控制-" + cmdStr);
        controlCommandMapper.insert(cmd);

        deviceMapper.update(null,
                new LambdaUpdateWrapper<Device>()
                        .eq(Device::getDeviceId, device.getDeviceId())
                        .set(Device::getLatestData, buildManualControlLatestData(device, request, cmdStr, now))
                        .set(Device::getLastManualAt, now)
                        .set(Device::getManualMode, true)
                        .set(Device::getManualExpireAt, now.plusMinutes(manualLockDurationMinutes)));
    }

    private List<Long> normalizeDeviceIds(List<Long> deviceIds) {
        if (deviceIds == null) {
            return List.of();
        }
        return deviceIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    private Map<String, Object> batchControlResult(int total, int success,
                                                   List<Map<String, Object>> failedDetails) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("success", success);
        result.put("failed", total - success);
        result.put("failedDetails", failedDetails);
        return result;
    }

    private String batchControlActionName(String action) {
        return "ON".equals(action) ? "开灯" : "关灯";
    }

    private String batchControlAuditAction(String action) {
        return "ON".equals(action) ? "DEVICE_BATCH_TURN_ON" : "DEVICE_BATCH_TURN_OFF";
    }

    /**
     * 记录审计日志。
     */
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
            auditLogService.save(logEntry);
        } catch (Exception e) {
            log.error("审计日志记录失败: {}", e.getMessage());
        }
    }

    /**
     * 获取客户端 IP 地址。
     */
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
}
