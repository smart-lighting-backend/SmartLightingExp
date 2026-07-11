package com.experiment.smartlightingexp.engine;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.experiment.smartlightingexp.config.MqttProperties;
import com.experiment.smartlightingexp.entity.AlarmRecord;
import com.experiment.smartlightingexp.entity.ControlCommand;
import com.experiment.smartlightingexp.entity.DecisionLog;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.entity.LightingPolicy;
import com.experiment.smartlightingexp.entity.Telemetry;
import com.experiment.smartlightingexp.mapper.AlarmRecordMapper;
import com.experiment.smartlightingexp.mapper.ControlCommandMapper;
import com.experiment.smartlightingexp.mapper.DecisionLogMapper;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.mapper.LightingPolicyMapper;
import com.experiment.smartlightingexp.mqtt.MqttPublisher;
import com.experiment.smartlightingexp.mqtt.SystemEventPublisher;
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
    private final AlarmRecordMapper alarmRecordMapper;
    private final DeviceMapper deviceMapper;
    private final MqttPublisher mqttPublisher;
    private final MqttProperties mqttProperties;
    private final ObjectMapper objectMapper;
    private final SystemEventPublisher systemEventPublisher;

    /** 手动控制后的 AI 锁定时间（分钟），由 MqttSubscriber 统一管理过期清除 */
    private static final long MANUAL_LOCK_MINUTES = 30;

    /** 同一设备两次评估的最短间隔（毫秒），防止线程池并发导致重复决策 */
    private static final long EVAL_DEDUP_MS = 5000;

    /** 设备最后评估时间戳 */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastEvalTime = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 对指定设备执行一次策略评估。
     * 由 MqttSubscriber 在收到新遥测后调用。
     */
    public void evaluate(String deviceId, Telemetry telemetry) {
        // 去重：同一设备 5 秒内不重复评估
        long evalNow = System.currentTimeMillis();
        Long last = lastEvalTime.put(deviceId, evalNow);
        if (last != null && (evalNow - last) < EVAL_DEDUP_MS) {
            log.debug("[{}] DecisionEngine skipped: dedup ({}ms)", deviceId, evalNow - last);
            return;
        }

        log.debug("[{}] DecisionEngine: lux={}, pir={}", deviceId,
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

        // 4. 执行或跳过（NO_MATCH 不再写库，减少无用日志堆积）
        if (matchedPolicy != null) {
            executeAction(deviceId, actionTaken, matchedPolicy, telemetry);
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

    /** 委托给 ConditionEvaluator（纯函数，保证云端与边缘评估逻辑一致） */
    public boolean matchesCondition(String conditionsJson, Telemetry telemetry) {
        return ConditionEvaluator.matchesCondition(conditionsJson, telemetry);
    }

    /** 委托给 ConditionEvaluator（支持模拟时间，用于策略模拟测试）。 */
    public boolean matchesCondition(String conditionsJson, Telemetry telemetry, java.time.LocalTime simulatedTime) {
        return ConditionEvaluator.matchesCondition(conditionsJson, telemetry, simulatedTime);
    }

    // ======================== 执行动作 ========================

    private void executeAction(String deviceId, String action, String policyName, Telemetry telemetry) {
        try {
            boolean skipControl = "NOTIFY".equals(action);

            // 1. 发布 MQTT 控制指令（NOTIFY 类型跳过）
            if (!skipControl) {
                Map<String, Object> cmdPayload = new HashMap<>();
                cmdPayload.put("action", action);
                cmdPayload.put("issuedAt", LocalDateTime.now().toString());
                cmdPayload.put("source", "AUTO");
                cmdPayload.put("reason", "策略引擎: " + policyName);
                String payload = objectMapper.writeValueAsString(cmdPayload);
                mqttPublisher.publish(mqttProperties.getTopicPrefix() + "/" + deviceId + "/command", payload, 0);

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

                // 2b. 更新设备 latestData — 写入自动控制元数据，确保前后端状态一致
                updateDeviceLatestData(deviceId, action, extractBrightness(action));
            }

            // 3. 记录 decision_log
            DecisionLog decisionLog = new DecisionLog();
            decisionLog.setDeviceId(deviceId);
            decisionLog.setInputSnapshot(buildSnapshotJson(telemetry));
            decisionLog.setMatchedPolicy(policyName);
            decisionLog.setActionTaken(action);
            decisionLog.setResult("MATCH_EXECUTED");
            decisionLogMapper.insert(decisionLog);

            // 4. 策略命中 → 附加联动动作
            LightingPolicy matched = lightingPolicyMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LightingPolicy>()
                            .eq(LightingPolicy::getName, policyName)
                            .eq(LightingPolicy::getDeleted, false));
            if (matched != null && matched.getConditions() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> conds = objectMapper.readValue(matched.getConditions(), Map.class);
                Object extra = conds.get("extraActions");
                if (extra instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ea = (Map<String, Object>) extra;
                    LocalDateTime now = LocalDateTime.now();

                    // 4a. 语音播报（支持自定义内容）
                    if (Boolean.TRUE.equals(ea.get("voiceAlert"))) {
                        String voiceContent = ea.get("voiceContent") instanceof String
                                && !((String) ea.get("voiceContent")).isBlank()
                                ? (String) ea.get("voiceContent")
                                : "策略触发: " + policyName + " → " + action;
                        Map<String, Object> voicePayload = new HashMap<>();
                        voicePayload.put("deviceId", deviceId);
                        voicePayload.put("type", "播报");
                        voicePayload.put("content", voiceContent);
                        voicePayload.put("source", "策略联动");
                        voicePayload.put("occurredAt", now.toString());
                        mqttPublisher.publish(mqttProperties.getTopicPrefix() + "/" + deviceId + "/voice/event",
                                objectMapper.writeValueAsString(voicePayload), 0);
                    }

                    // 4b. 自动拍照（发布视觉事件）
                    if (Boolean.TRUE.equals(ea.get("capturePhoto"))) {
                        Map<String, Object> visionPayload = new HashMap<>();
                        visionPayload.put("deviceId", deviceId);
                        visionPayload.put("eventType", "策略联动拍照");
                        visionPayload.put("confidence", 1.0);
                        visionPayload.put("snapshotRef", "policy/snapshot/" + deviceId + "_" + System.currentTimeMillis() + ".jpg");
                        visionPayload.put("occurredAt", now.toString());
                        mqttPublisher.publish(mqttProperties.getTopicPrefix() + "/" + deviceId + "/vision/event",
                                objectMapper.writeValueAsString(visionPayload), 0);
                    }

                    // 4c. 产生自定义告警（去重 + MQTT 事件）
                    if (Boolean.TRUE.equals(ea.get("generateAlert"))) {
                        String alertType = ea.get("alertType") instanceof String
                                ? (String) ea.get("alertType") : "POLICY_ALERT";
                        String alertLevel = ea.get("alertLevel") instanceof String
                                ? (String) ea.get("alertLevel") : "WARNING";
                        String alertContent = ea.get("alertContent") instanceof String
                                && !((String) ea.get("alertContent")).isBlank()
                                ? (String) ea.get("alertContent")
                                : "策略 " + policyName + " 触发 → " + action;
                        // 去重：同一设备同一类型已有未恢复告警则跳过
                        Long existing = alarmRecordMapper.selectCount(
                                Wrappers.<AlarmRecord>lambdaQuery()
                                        .eq(AlarmRecord::getDeviceId, deviceId)
                                        .eq(AlarmRecord::getType, alertType)
                                        .in(AlarmRecord::getStatus, List.of("ACTIVE", "ACKNOWLEDGED")));
                        if (existing == 0) {
                            AlarmRecord alarm = new AlarmRecord();
                            alarm.setDeviceId(deviceId);
                            alarm.setType(alertType);
                            alarm.setLevel(alertLevel);
                            alarm.setStatus("ACTIVE");
                            alarm.setReason(alertContent);
                            alarm.setStartAt(now);
                            alarmRecordMapper.insert(alarm);
                            systemEventPublisher.publishAlarmEvent("created", alarm);
                        }
                    }
                }
            }

            log.info("自动控制 [{}]: {} (策略={})", deviceId, action, policyName);
        } catch (Exception e) {
            log.error("决策引擎执行失败 [{}]: {}", deviceId, e.getMessage());
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
            }
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

    /**
     * 将自动控制结果合并写入设备 latestData，保留遥测快照的同时注入控制元数据。
     */
    @SuppressWarnings("unchecked")
    private void updateDeviceLatestData(String deviceId, String action, Integer brightness) {
        try {
            Device device = deviceMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Device>()
                            .eq(Device::getDeviceId, deviceId));
            if (device == null) return;

            Map<String, Object> merged = new java.util.LinkedHashMap<>();
            if (device.getLatestData() != null && !device.getLatestData().isBlank()) {
                try {
                    Map<String, Object> existing = objectMapper.readValue(device.getLatestData(), Map.class);
                    merged.putAll(existing);
                } catch (Exception ignored) {}
            }
            merged.put("action", action);
            if (brightness != null) merged.put("brightness", brightness);
            else if ("ON".equals(action)) merged.put("brightness", 100);
            else if ("OFF".equals(action)) merged.put("brightness", 0);
            merged.put("controlSource", "AUTO");
            merged.put("controlIssuedAt", LocalDateTime.now().toString());

            deviceMapper.update(null,
                    Wrappers.<Device>lambdaUpdate()
                            .eq(Device::getDeviceId, deviceId)
                            .set(Device::getLatestData, objectMapper.writeValueAsString(merged)));
        } catch (Exception e) {
            log.error("更新设备latestData失败 [{}]: {}", deviceId, e.getMessage());
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
