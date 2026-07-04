package com.experiment.smartlightingexp.engine;

import com.experiment.smartlightingexp.entity.ControlCommand;
import com.experiment.smartlightingexp.entity.DecisionLog;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.entity.LightingPolicy;
import com.experiment.smartlightingexp.entity.Telemetry;
import com.experiment.smartlightingexp.mapper.ControlCommandMapper;
import com.experiment.smartlightingexp.mapper.DecisionLogMapper;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.mapper.LightingPolicyMapper;
import com.experiment.smartlightingexp.mqtt.MqttPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略决策引擎 — AI 自动控制核心。
 * 每次收到新遥测数据时触发，读取 lighting_policy 表，
 * 解析 conditions JSON，匹配当前遥测，决定是否下发控制指令。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionEngine {

    private final LightingPolicyMapper lightingPolicyMapper;
    private final ControlCommandMapper controlCommandMapper;
    private final DecisionLogMapper decisionLogMapper;
    private final DeviceMapper deviceMapper;
    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;

    /** 手动控制后的 AI 锁定时间（分钟），由 MqttSubscriber 统一管理过期清除 */
    private static final long MANUAL_LOCK_MINUTES = 30;

    /**
     * 对指定设备执行一次策略评估。
     * 由 MqttSubscriber 在收到新遥测后调用。
     */
    public void evaluate(String deviceId, Telemetry telemetry) {
        log.info("[{}] DecisionEngine evaluate: lux={}, pir={}", deviceId,
                telemetry.getIlluminance(), telemetry.getPir());

        // 1. 获取设备信息，检查手动锁定
        Device device = deviceMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Device>()
                        .eq(Device::getDeviceId, deviceId));
        if (device == null || Boolean.FALSE.equals(device.getEnabled())) {
            return;
        }
        if (isManuallyLocked(device)) {
            log.debug("[{}] DecisionEngine skipped: manually locked", deviceId);
            return;
        }

        // 2. 查询启用的策略（按优先级升序，数字越小优先级越高）
        List<LightingPolicy> policies = lightingPolicyMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LightingPolicy>()
                        .eq(LightingPolicy::getEnabled, true)
                        .eq(LightingPolicy::getDeleted, false)
                        .orderByAsc(LightingPolicy::getPriority));

        if (policies.isEmpty()) {
            log.debug("[{}] No enabled policies", deviceId);
            return;
        }

        // 3. 遍历策略，找到第一个匹配的
        String matchedPolicy = null;
        String actionTaken = null;

        for (LightingPolicy policy : policies) {
            if (matchesCondition(policy.getConditions(), telemetry)) {
                matchedPolicy = policy.getName();
                actionTaken = policy.getAction();
                break; // 优先级最高的命中
            }
        }

        // 4. 执行或跳过
        if (matchedPolicy != null) {
            executeAction(deviceId, actionTaken, matchedPolicy, telemetry);
        } else {
            logNoMatch(deviceId, telemetry);
        }
    }

    // ======================== 手动锁定检测 ========================

    private boolean isManuallyLocked(Device device) {
        if (!Boolean.TRUE.equals(device.getManualMode())) return false;
        if (device.getManualExpireAt() == null) return false;
        // 过期则由 MqttSubscriber 下一次遥测时清除，此处不重复写库
        return device.getManualExpireAt().isAfter(LocalDateTime.now());
    }

    // ======================== 条件匹配器 ========================

    @SuppressWarnings("unchecked")
    boolean matchesCondition(String conditionsJson, Telemetry telemetry) {
        if (conditionsJson == null || conditionsJson.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> conds = objectMapper.readValue(conditionsJson, Map.class);
            for (Map.Entry<String, Object> entry : conds.entrySet()) {
                if (!evaluateSingle(entry.getKey(), entry.getValue(), telemetry)) {
                    return false; // AND 逻辑：任一条件不满足则整体不匹配
                }
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to parse conditions: {}", conditionsJson, e);
            return false;
        }
    }

    private boolean evaluateSingle(String key, Object value, Telemetry t) {
        if (value == null) return false;
        return switch (key) {
            case "lux_lt" -> t.getIlluminance() != null && t.getIlluminance().compareTo(num(value)) < 0;
            case "lux_gt" -> t.getIlluminance() != null && t.getIlluminance().compareTo(num(value)) > 0;
            case "temp_lt" -> t.getTemperature() != null && t.getTemperature().compareTo(num(value)) < 0;
            case "temp_gt" -> t.getTemperature() != null && t.getTemperature().compareTo(num(value)) > 0;
            case "humidity_lt" -> t.getHumidity() != null && t.getHumidity().compareTo(num(value)) < 0;
            case "humidity_gt" -> t.getHumidity() != null && t.getHumidity().compareTo(num(value)) > 0;
            case "pir" -> t.getPir() != null && t.getPir().intValue() == intVal(value);
            case "traffic_gt" -> t.getTrafficFlow() != null && t.getTrafficFlow() > intVal(value);
            case "traffic_lt" -> t.getTrafficFlow() != null && t.getTrafficFlow() < intVal(value);
            case "time_range" -> isInTimeRange(value.toString());
            default -> {
                // 跳过 group / startTime / extraActions 等元数据字段
                yield true;
            }
        };
    }

    private boolean isInTimeRange(String range) {
        // 格式: "23:00-05:59"
        String[] parts = range.split("-");
        if (parts.length != 2) return false;
        try {
            LocalTime start = LocalTime.parse(parts[0] + ":00");
            LocalTime end = LocalTime.parse(parts[1] + ":00");
            LocalTime now = LocalTime.now();
            if (start.isBefore(end) || start.equals(end)) {
                // 同一天内: 23:00-05:59 这种跨天需要用下面逻辑
                // 这里处理不跨天情况
                return !now.isBefore(start) && !now.isAfter(end);
            } else {
                // 跨天: 23:00-05:59
                return !now.isBefore(start) || !now.isAfter(end);
            }
        } catch (Exception e) {
            log.warn("Invalid time_range: {}", range);
            return false;
        }
    }

    private java.math.BigDecimal num(Object v) {
        return new java.math.BigDecimal(v.toString());
    }

    private int intVal(Object v) {
        return ((Number) v).intValue();
    }

    // ======================== 执行动作 ========================

    private void executeAction(String deviceId, String action, String policyName, Telemetry telemetry) {
        try {
            // 1. 发布 MQTT 控制指令
            Map<String, Object> cmdPayload = new HashMap<>();
            cmdPayload.put("action", action);
            cmdPayload.put("issuedAt", LocalDateTime.now().toString());
            cmdPayload.put("source", "AUTO");
            cmdPayload.put("reason", "策略引擎: " + policyName);
            String payload = objectMapper.writeValueAsString(cmdPayload);
            mqttPublisher.publish("streetlight/" + deviceId + "/command", payload, 1);

            // 2. 记录 control_command
            ControlCommand cmd = new ControlCommand();
            cmd.setDeviceId(deviceId);
            cmd.setAction(action);
            cmd.setBrightness(extractBrightness(action));
            cmd.setSource("AUTO");
            cmd.setStatus("SENT");
            cmd.setIssuedAt(LocalDateTime.now());
            cmd.setResultDetail("策略引擎-" + policyName);
            controlCommandMapper.insert(cmd);

            // 3. 记录 decision_log
            DecisionLog decisionLog = new DecisionLog();
            decisionLog.setDeviceId(deviceId);
            decisionLog.setInputSnapshot(buildSnapshotJson(telemetry));
            decisionLog.setMatchedPolicy(policyName);
            decisionLog.setActionTaken(action);
            decisionLog.setResult("MATCH_EXECUTED");
            decisionLogMapper.insert(decisionLog);

            log.info("[{}] Auto control → {} (policy={})", deviceId, action, policyName);
        } catch (Exception e) {
            log.error("[{}] DecisionEngine executeAction failed: {}", deviceId, e.getMessage());
            // 记录失败的 control_command
            try {
                ControlCommand failedCmd = new ControlCommand();
                failedCmd.setDeviceId(deviceId);
                failedCmd.setAction(action);
                failedCmd.setSource("AUTO");
                failedCmd.setStatus("FAILED");
                failedCmd.setIssuedAt(LocalDateTime.now());
                failedCmd.setResultDetail("MQTT下发失败-" + e.getMessage());
                controlCommandMapper.insert(failedCmd);
            } catch (Exception ex) {
                log.warn("[{}] Failed to persist failed control_command: {}", deviceId, ex.getMessage());
            }
            // 记录失败的 decision_log
            try {
                DecisionLog failedLog = new DecisionLog();
                failedLog.setDeviceId(deviceId);
                failedLog.setInputSnapshot(buildSnapshotJson(telemetry));
                failedLog.setMatchedPolicy(policyName);
                failedLog.setActionTaken(action);
                failedLog.setResult("MATCH_FAILED");
                decisionLogMapper.insert(failedLog);
            } catch (Exception ex) {
                log.warn("[{}] Failed to persist failed decision_log: {}", deviceId, ex.getMessage());
            }
        }
    }

    private void logNoMatch(String deviceId, Telemetry telemetry) {
        try {
            DecisionLog logEntry = new DecisionLog();
            logEntry.setDeviceId(deviceId);
            logEntry.setInputSnapshot(buildSnapshotJson(telemetry));
            logEntry.setResult("NO_MATCH");
            decisionLogMapper.insert(logEntry);
        } catch (Exception e) {
            log.warn("[{}] Failed to log NO_MATCH: {}", deviceId, e.getMessage());
        }
    }

    private Integer extractBrightness(String action) {
        if (action == null || !action.startsWith("DIMMING(")) return null;
        try {
            String num = action.replace("DIMMING(", "").replace(")", "");
            return Integer.parseInt(num);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildSnapshotJson(Telemetry telemetry) {
        try {
            Map<String, Object> snap = new HashMap<>();
            snap.put("illuminance", telemetry.getIlluminance());
            snap.put("temperature", telemetry.getTemperature());
            snap.put("humidity", telemetry.getHumidity());
            snap.put("pm25", telemetry.getPm25());
            snap.put("aqi", telemetry.getAqi());
            snap.put("pir", telemetry.getPir());
            snap.put("trafficFlow", telemetry.getTrafficFlow());
            snap.put("collectedAt", telemetry.getCollectedAt() != null
                    ? telemetry.getCollectedAt().toString() : null);
            return objectMapper.writeValueAsString(snap);
        } catch (Exception e) {
            return "{}";
        }
    }
}
