package com.experiment.smartlightingexp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.experiment.smartlightingexp.common.BusinessException;
import com.experiment.smartlightingexp.dto.AssistantChatResponse;
import com.experiment.smartlightingexp.entity.*;
import com.experiment.smartlightingexp.mapper.*;
import com.experiment.smartlightingexp.service.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AssistantService {

    private static final String POLICY_TYPE_THRESHOLD = "THRESHOLD";
    private static final String CONDITION_LUX_LT = "lux_lt";
    private static final String CONDITION_LUX_GT = "lux_gt";
    private static final Pattern SET_THRESHOLD_PATTERN = Pattern.compile(
            "(?:光照阈值|亮灯阈值|开灯阈值|阈值).{0,12}?(?:调到|调整到|设置为|设为|改为|改成|=|:|：)?\\s*(-?\\d+(?:\\.\\d+)?)"
    );

    private final MaxKbClient maxKbClient;
    private final LightingPolicyService lightingPolicyService;
    private final ObjectMapper objectMapper;
    private final DeviceService deviceService;
    private final AlarmRecordService alarmRecordService;
    private final ControlCommandMapper controlCommandMapper;

    public AssistantChatResponse chat(String rawMessage) {
        String message = rawMessage.trim();
        Optional<BigDecimal> threshold = parseThresholdCommand(message);
        if (threshold.isPresent()) {
            BigDecimal value = threshold.get();
            LightingPolicy policy = updateLuxLtThreshold(value);
            String content = "已将光照触发阈值调整为 " + plain(value) + " lux。";
            return AssistantChatResponse.thresholdUpdated(content, Map.of(
                    "name", "SET_LUX_LT_THRESHOLD",
                    "luxLt", value,
                    "policyId", policy.getId(),
                    "policyName", policy.getName()
            ));
        }

        return AssistantChatResponse.knowledge(maxKbClient.chat(message));
    }

    private Optional<BigDecimal> parseThresholdCommand(String message) {
        Matcher matcher = SET_THRESHOLD_PATTERN.matcher(message);
        if (!matcher.find()) {
            return Optional.empty();
        }

        BigDecimal threshold = new BigDecimal(matcher.group(1));
        if (threshold.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(400, "光照阈值不能小于0");
        }
        return Optional.of(threshold);
    }

    private LightingPolicy updateLuxLtThreshold(BigDecimal luxLt) {
        LightingPolicy policy = findLuxThresholdPolicy();
        if (policy == null) {
            throw new BusinessException(404, "光照阈值策略不存在");
        }

        Map<String, Object> conditions = parseConditions(policy.getConditions());
        BigDecimal luxGt = toBigDecimal(conditions.get(CONDITION_LUX_GT));
        if (luxGt != null && luxLt.compareTo(luxGt) >= 0) {
            throw new BusinessException(400, "开灯光照阈值必须小于关灯光照阈值");
        }

        conditions.put(CONDITION_LUX_LT, luxLt);
        try {
            policy.setConditions(objectMapper.writeValueAsString(conditions));
            lightingPolicyService.updateById(policy);
            return policy;
        } catch (Exception e) {
            throw new BusinessException(500, "策略条件 JSON 序列化失败");
        }
    }

    private LightingPolicy findLuxThresholdPolicy() {
        List<LightingPolicy> policies = lightingPolicyService.list().stream()
                .filter(policy -> POLICY_TYPE_THRESHOLD.equals(policy.getPolicyType()))
                .filter(policy -> !Boolean.TRUE.equals(policy.getDeleted()))
                .sorted((left, right) -> Integer.compare(priority(left), priority(right)))
                .toList();
        if (policies.isEmpty()) {
            return null;
        }

        return policies.stream()
                .filter(policy -> hasLuxCondition(policy.getConditions()))
                .findFirst()
                .orElse(policies.get(0));
    }

    private int priority(LightingPolicy policy) {
        return policy.getPriority() != null ? policy.getPriority() : Integer.MAX_VALUE;
    }

    private boolean hasLuxCondition(String conditions) {
        return conditions != null
                && (conditions.contains("\"" + CONDITION_LUX_LT + "\"")
                || conditions.contains("\"" + CONDITION_LUX_GT + "\""));
    }

    private Map<String, Object> parseConditions(String conditionsJson) {
        if (conditionsJson == null || conditionsJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(conditionsJson, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new BusinessException(500, "策略条件 JSON 格式错误");
        }
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        return new BigDecimal(value.toString());
    }

    // ───────────── 设备诊断 ─────────────
    public AssistantChatResponse diagnose(String deviceId, String question) {
        Device device = deviceService.lambdaQuery()
                .eq(Device::getDeviceId, deviceId).eq(Device::getDeleted, false).one();
        if (device == null) return AssistantChatResponse.knowledge("设备 " + deviceId + " 不存在。");

        StringBuilder ctx = new StringBuilder();
        ctx.append("你是一个智慧路灯维修专家。请根据以下设备实时状态数据，分析可能原因并给出维修建议。\n\n");
        ctx.append("设备: ").append(device.getDeviceId())
                .append(" (").append(device.getName() != null ? device.getName() : "").append(")\n");
        ctx.append("区域: ").append(device.getArea() != null ? device.getArea() : "未知").append("\n");
        ctx.append("健康评分: ").append(device.getHealthScore() != null ? device.getHealthScore() : "未评估").append("\n");
        ctx.append("状态: ").append(statusLabel(device.getStatus())).append("\n");

        // 最近告警
        List<AlarmRecord> alarms = alarmRecordService.list(
                new LambdaQueryWrapper<AlarmRecord>().eq(AlarmRecord::getDeviceId, deviceId)
                        .orderByDesc(AlarmRecord::getStartAt).last("LIMIT 5"));
        if (!alarms.isEmpty()) {
            ctx.append("最近告警:\n");
            for (AlarmRecord a : alarms) {
                ctx.append("  - ").append(a.getType()).append(" (")
                        .append(a.getStartAt()).append(")");
                if (a.getRecoverAt() != null) ctx.append(" 已恢复");
                ctx.append("\n");
            }
        } else {
            ctx.append("最近告警: 无\n");
        }

        // 指令响应率
        List<ControlCommand> cmds = controlCommandMapper.selectList(
                new LambdaQueryWrapper<ControlCommand>().eq(ControlCommand::getDeviceId, deviceId)
                        .ge(ControlCommand::getIssuedAt, java.time.LocalDateTime.now().minusDays(7)));
        long acked = cmds.stream().filter(c -> c.getAckAt() != null).count();
        ctx.append("指令响应率: ").append(cmds.isEmpty() ? "无记录" :
                Math.round(100.0 * acked / cmds.size()) + "% (" + acked + "/" + cmds.size() + ")").append("\n");

        // 遥测快照
        if (device.getLatestData() != null && !device.getLatestData().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> tel = objectMapper.readValue(device.getLatestData(), Map.class);
                ctx.append("当前遥测: ").append(tel).append("\n");
            } catch (Exception ignored) {}
        }

        if (question != null && !question.isBlank()) {
            ctx.append("\n用户问题: ").append(question).append("\n");
        } else {
            ctx.append("\n请分析该设备健康状态并给出维修建议。\n");
        }

        try {
            return AssistantChatResponse.knowledge(maxKbClient.chat(ctx.toString()));
        } catch (Exception e) {
            return AssistantChatResponse.knowledge(
                    "设备 " + deviceId + " 实时状态：健康评分 " +
                    (device.getHealthScore() != null ? device.getHealthScore() : "未评估") +
                    "，状态 " + statusLabel(device.getStatus()) +
                    "，最近告警 " + alarms.size() + " 条。" +
                    "\n(MaxKB 服务不可用，以上为基础数据摘要，请人工判断)");
        }
    }

    private String statusLabel(Integer s) {
        return switch (s != null ? s : 0) {
            case 1 -> "在线";
            case 2 -> "离线";
            case 3 -> "异常";
            default -> "停用";
        };
    }

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
