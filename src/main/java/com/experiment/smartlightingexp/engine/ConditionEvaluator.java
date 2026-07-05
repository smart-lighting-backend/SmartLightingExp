package com.experiment.smartlightingexp.engine;

import com.experiment.smartlightingexp.entity.Telemetry;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Map;

/**
 * 条件评估器 — 纯函数，零 Spring 依赖。
 * 与 DecisionEngine 的评估逻辑完全一致，可复用至边缘节点、模拟器或单元测试。
 *
 * 评估规则：conditions JSON 中所有 key-value 为 AND 逻辑，
 * 任一条件不满足则整体不匹配。跳过 group/startTime/endTime/extraActions 等元数据字段。
 */
public class ConditionEvaluator {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 判断一组 conditions JSON 是否匹配给定的遥测数据。
     *
     * @param conditionsJson 策略条件 JSON，如 {"lux_lt":30,"temp_gt":35}
     * @param telemetry      遥测快照
     * @return true=条件全部满足
     */
    public static boolean matchesCondition(String conditionsJson, Telemetry telemetry) {
        if (conditionsJson == null || conditionsJson.isBlank()) return false;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> conds = objectMapper.readValue(conditionsJson, Map.class);
            for (Map.Entry<String, Object> entry : conds.entrySet()) {
                if (!evaluateSingle(entry.getKey(), entry.getValue(), telemetry)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 评估单个条件 key=value 是否命中当前遥测数据。 */
    public static boolean evaluateSingle(String key, Object value, Telemetry t) {
        if (value == null) return false;
        return switch (key) {
            case "lux_lt"       -> t.getIlluminance() != null && t.getIlluminance().compareTo(num(value)) < 0;
            case "lux_gt"       -> t.getIlluminance() != null && t.getIlluminance().compareTo(num(value)) > 0;
            case "temp_lt"      -> t.getTemperature() != null && t.getTemperature().compareTo(num(value)) < 0;
            case "temp_gt"      -> t.getTemperature() != null && t.getTemperature().compareTo(num(value)) > 0;
            case "humidity_lt"  -> t.getHumidity() != null && t.getHumidity().compareTo(num(value)) < 0;
            case "humidity_gt"  -> t.getHumidity() != null && t.getHumidity().compareTo(num(value)) > 0;
            case "pir"          -> t.getPir() != null && t.getPir().intValue() == intVal(value);
            case "traffic_gt"   -> t.getTrafficFlow() != null && t.getTrafficFlow() > intVal(value);
            case "traffic_lt"   -> t.getTrafficFlow() != null && t.getTrafficFlow() < intVal(value);
            case "time_range"   -> isInTimeRange(value.toString());
            default             -> true; // 跳过 group/startTime/extraActions 等元数据
        };
    }

    // ── 工具方法 ────────────────────────────────────────────────────────────

    static BigDecimal num(Object v) { return new BigDecimal(v.toString()); }
    static int intVal(Object v) { return ((Number) v).intValue(); }

    static boolean isInTimeRange(String range) {
        String[] parts = range.split("-");
        if (parts.length != 2) return false;
        try {
            LocalTime start = LocalTime.parse(parts[0] + ":00");
            LocalTime end   = LocalTime.parse(parts[1] + ":00");
            LocalTime now   = LocalTime.now();
            if (start.isBefore(end) || start.equals(end)) {
                return !now.isBefore(start) && !now.isAfter(end);
            } else {
                return !now.isBefore(start) || !now.isAfter(end);
            }
        } catch (Exception e) {
            return false;
        }
    }
}
