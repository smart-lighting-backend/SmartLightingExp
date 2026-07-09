package com.experiment.smartlightingexp.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.experiment.smartlightingexp.engine.ConditionEvaluator;
import com.experiment.smartlightingexp.entity.*;
import com.experiment.smartlightingexp.mapper.*;
import com.experiment.smartlightingexp.tdengine.TelemetryDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final TelemetryDao telemetryDao;
    private final LightingPolicyMapper lightingPolicyMapper;
    private final DecisionLogMapper decisionLogMapper;
    private final ObjectMapper objectMapper;

    private final Random random = new Random();
    private final AtomicInteger totalEdgeDecisions = new AtomicInteger(0);
    private final AtomicInteger edgeHits = new AtomicInteger(0);
    private volatile LocalDateTime lastSimulatedAt;

    @PostConstruct
    public void initCounters() {
        try {
            int total = decisionLogMapper.selectCount(
                    new LambdaQueryWrapper<DecisionLog>()
                            .likeRight(DecisionLog::getResult, "EDGE_")).intValue();
            int hits = decisionLogMapper.selectCount(
                    new LambdaQueryWrapper<DecisionLog>()
                            .eq(DecisionLog::getResult, "EDGE_MATCH_EXECUTED")).intValue();
            totalEdgeDecisions.set(total);
            edgeHits.set(hits);
            log.info("[EdgeSim] Initialized counters from DB: total={}, hits={}", total, hits);
        } catch (Exception e) {
            log.warn("[EdgeSim] Failed to init counters from DB: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 120_000L)
    public void simulate() {
        log.info("[EdgeSim] Tick — starting evaluation round");
        try {
            // ① 选取在线设备
            List<Device> online = deviceMapper.selectList(
                    new LambdaQueryWrapper<Device>()
                            .eq(Device::getStatus, 1)
                            .eq(Device::getDeleted, false));
            if (online.isEmpty()) {
                log.info("[EdgeSim] No online devices found, skip this round");
                lastSimulatedAt = LocalDateTime.now();
                return;
            }

            // 随机选至多 5 台作为"有边缘能力"的设备
            Collections.shuffle(online, random);
            List<Device> edgeDevices = online.subList(0, Math.min(5, online.size()));

            // ② 获取启用的策略（模拟边缘节点本地缓存）
            List<LightingPolicy> policies = lightingPolicyMapper.selectList(
                    new LambdaQueryWrapper<LightingPolicy>()
                            .eq(LightingPolicy::getEnabled, true)
                            .eq(LightingPolicy::getDeleted, false)
                            .orderByAsc(LightingPolicy::getPriority));

            if (policies.isEmpty()) {
                log.info("[EdgeSim] No enabled policies, skip this round");
                lastSimulatedAt = LocalDateTime.now();
                return;
            }

            int decisions = 0;
            int hits = 0;
            int skippedNoTelemetry = 0;
            for (Device device : edgeDevices) {
                // 获取最新一条遥测：TDengine → device.latestData 快照兜底
                Telemetry latest = telemetryDao.latest(device.getDeviceId());
                if (latest == null && device.getLatestData() != null) {
                    try {
                        latest = objectMapper.readValue(device.getLatestData(), Telemetry.class);
                    } catch (Exception e) {
                        log.debug("[EdgeSim] {} failed to parse latestData snapshot", device.getDeviceId());
                    }
                }
                if (latest == null) {
                    skippedNoTelemetry++;
                    continue;
                }

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
                if (matchedPolicy != null) hits++;
            }

            totalEdgeDecisions.addAndGet(decisions);
            edgeHits.addAndGet(hits);
            lastSimulatedAt = LocalDateTime.now();
            log.info("[EdgeSim] Round complete: {} candidates, {} decisions ({} hits), {} skipped (no telemetry), total={}, hits={}",
                    edgeDevices.size(), decisions, hits, skippedNoTelemetry, totalEdgeDecisions.get(), edgeHits.get());

        } catch (Exception e) {
            log.error("[EdgeSim] Simulation failed: {}", e.getMessage(), e);
        }
    }

    public int getTotalEdgeDecisions() {
        return totalEdgeDecisions.get();
    }

    public int getEdgeHits() {
        return edgeHits.get();
    }

    public LocalDateTime getLastSimulatedAt() {
        return lastSimulatedAt;
    }

    private String buildSnapshot(Telemetry t) {
        try {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("illuminance", t.getIlluminance());
            snap.put("temperature", t.getTemperature());
            snap.put("humidity", t.getHumidity());
            snap.put("pm25", t.getPm25());
            snap.put("aqi", t.getAqi());
            snap.put("pir", t.getPir());
            snap.put("trafficFlow", t.getTrafficFlow());
            snap.put("collectedAt", t.getCollectedAt() != null ? t.getCollectedAt().toString() : null);
            return objectMapper.writeValueAsString(snap);
        } catch (Exception e) {
            return "{}";
        }
    }
}
