package com.experiment.smartlightingexp.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.experiment.smartlightingexp.engine.ConditionEvaluator;
import com.experiment.smartlightingexp.entity.*;
import com.experiment.smartlightingexp.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 边缘 AI 决策模拟器。
 * 模拟路灯内置边缘节点在断网时的本地决策能力：
 *   ① 读取本地缓存的策略（复用 lighting_policy 表）
 *   ② 用 ConditionEvaluator 本地评估条件（与云端 DecisionEngine 逻辑一致）
 *   ③ 写入 decision_log（result 含 EDGE_ 前缀，区别于云端 AUTO）
 *
 * 调度：每 60 秒扫描一次，每次随机选取至多 5 台在线设备作为"边缘节点"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EdgeNodeSimulator {

    private final DeviceMapper deviceMapper;
    private final TelemetryMapper telemetryMapper;
    private final LightingPolicyMapper lightingPolicyMapper;
    private final DecisionLogMapper decisionLogMapper;
    private final ObjectMapper objectMapper;

    private final Random random = new Random();
    private int totalEdgeDecisions = 0;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 120_000L)
    public void simulate() {
        try {
            // ① 选取在线设备
            List<Device> online = deviceMapper.selectList(
                    new LambdaQueryWrapper<Device>()
                            .eq(Device::getStatus, 1)
                            .eq(Device::getDeleted, false));
            if (online.isEmpty()) return;

            // 随机选至多 5 台作为"有边缘能力"的设备
            Collections.shuffle(online, random);
            List<Device> edgeDevices = online.subList(0, Math.min(5, online.size()));

            // ② 获取启用的策略（模拟边缘节点本地缓存）
            List<LightingPolicy> policies = lightingPolicyMapper.selectList(
                    new LambdaQueryWrapper<LightingPolicy>()
                            .eq(LightingPolicy::getEnabled, true)
                            .eq(LightingPolicy::getDeleted, false)
                            .orderByAsc(LightingPolicy::getPriority));

            if (policies.isEmpty()) return;

            int decisions = 0;
            for (Device device : edgeDevices) {
                // 获取最新一条遥测
                Telemetry latest = telemetryMapper.selectOne(
                        new LambdaQueryWrapper<Telemetry>()
                                .eq(Telemetry::getDeviceId, device.getDeviceId())
                                .orderByDesc(Telemetry::getCollectedAt)
                                .last("LIMIT 1"));
                if (latest == null) continue;

                // ③ 边缘本地评估（与云端逻辑一致）
                String matchedPolicy = null;
                String matchedAction = null;
                for (LightingPolicy policy : policies) {
                    if (ConditionEvaluator.matchesCondition(policy.getConditions(), latest)) {
                        matchedPolicy = policy.getName();
                        matchedAction = policy.getAction();
                        break;
                    }
                }

                // ④ 写入 decision_log（source=EDGE）
                DecisionLog logEntry = new DecisionLog();
                logEntry.setDeviceId(device.getDeviceId());
                logEntry.setInputSnapshot(buildSnapshot(latest));
                logEntry.setMatchedPolicy(matchedPolicy);
                logEntry.setActionTaken(matchedAction);
                logEntry.setResult(matchedPolicy != null ? "EDGE_MATCH_EXECUTED" : "EDGE_NO_MATCH");
                decisionLogMapper.insert(logEntry);
                decisions++;
            }

            totalEdgeDecisions += decisions;
            log.info("[EdgeSim] {} edge devices evaluated ({} decisions), total={}",
                    edgeDevices.size(), decisions, totalEdgeDecisions);

        } catch (Exception e) {
            log.error("[EdgeSim] Simulation failed: {}", e.getMessage());
        }
    }

    public int getTotalEdgeDecisions() {
        return totalEdgeDecisions;
    }

    private String buildSnapshot(Telemetry t) {
        try {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("illuminance", t.getIlluminance());
            snap.put("temperature", t.getTemperature());
            snap.put("humidity", t.getHumidity());
            snap.put("pir", t.getPir());
            snap.put("trafficFlow", t.getTrafficFlow());
            snap.put("collectedAt", t.getCollectedAt() != null ? t.getCollectedAt().toString() : null);
            return objectMapper.writeValueAsString(snap);
        } catch (Exception e) {
            return "{}";
        }
    }
}
