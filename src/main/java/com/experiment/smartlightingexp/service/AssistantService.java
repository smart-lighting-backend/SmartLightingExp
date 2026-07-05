package com.experiment.smartlightingexp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.experiment.smartlightingexp.common.BusinessException;
import com.experiment.smartlightingexp.dto.AssistantChatResponse;
import com.experiment.smartlightingexp.entity.*;
import com.experiment.smartlightingexp.mapper.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantService {

    // ── 本地正则快速路径 ─────────────────────────────────────────────────────
    private static final String CONDITION_LUX_LT = "lux_lt";
    private static final String CONDITION_LUX_GT = "lux_gt";
    private static final Pattern SET_THRESHOLD_PATTERN = Pattern.compile(
            "(?:光照阈值|亮灯阈值|开灯阈值|阈值).{0,12}?(?:调到|调整到|设置为|设为|改为|改成|=|:|：)?\\s*(-?\\d+(?:\\.\\d+)?)"
    );

    // ── AI 意图识别 ─────────────────────────────────────────────────────────
    private static final String INTENT_SYSTEM_PROMPT = """
你是一个智慧路灯系统的运维助手。你可以帮助用户修改照明策略参数。

当前系统可调整的策略：
  策略ID=2「深夜节能调光」(type=SCENE)，当前参数：lux_lt=30, temp_lt=5, startTime=23:00, endTime=05:00

可调整的参数及其范围：
  - lux_lt: 开灯光照阈值(lux)，范围 1-200
  - lux_gt: 关灯光照阈值(lux)，范围 1-1000（必须大于 lux_lt）
  - temp_lt: 低温触发阈值(℃)，范围 -20~50
  - startTime: 生效开始时间，格式 HH:mm
  - endTime: 生效结束时间，格式 HH:mm
  - brightness: 调光亮度(%)，范围 0-100
  - enabled: 启用/停用策略，true/false

你必须**只返回**一个 JSON 对象，不要包含任何其他文字、解释、代码块标记：

{"intent":"UPDATE_POLICY","params":{"lux_lt":30}}

如果用户意图是修改策略参数，intent 为 UPDATE_POLICY，params 只包含要修改的参数。
如果用户只是提问、咨询、闲聊、或意图无法识别，intent 为 CHAT，params 为空对象 {}。
""";

    // ── 参数白名单 ───────────────────────────────────────────────────────────
    private static final Set<String> PARAM_WHITELIST = Set.of(
            "lux_lt", "lux_gt", "temp_lt", "startTime", "endTime", "brightness", "enabled"
    );

    private static final Set<String> NUMERIC_PARAMS = Set.of(
            "lux_lt", "lux_gt", "temp_lt", "brightness"
    );

    private static final Map<String, BigDecimal> PARAM_MIN = Map.of(
            "lux_lt", new BigDecimal("1"),
            "lux_gt", new BigDecimal("1"),
            "temp_lt", new BigDecimal("-20"),
            "brightness", BigDecimal.ZERO
    );

    private static final Map<String, BigDecimal> PARAM_MAX = Map.of(
            "lux_lt", new BigDecimal("200"),
            "lux_gt", new BigDecimal("1000"),
            "temp_lt", new BigDecimal("50"),
            "brightness", new BigDecimal("100")
    );

    private final MaxKbClient maxKbClient;
    private final LightingPolicyService lightingPolicyService;
    private final ObjectMapper objectMapper;
    private final DeviceService deviceService;
    private final AlarmRecordService alarmRecordService;
    private final ControlCommandMapper controlCommandMapper;

    // ── 主入口 ──────────────────────────────────────────────────────────────

    public AssistantChatResponse chat(String rawMessage) {
        String message = rawMessage.trim();

        // 第一段：本地正则快速路径
        Optional<BigDecimal> threshold = parseThresholdCommand(message);
        if (threshold.isPresent()) {
            return executeThresholdUpdate(threshold.get());
        }

        // 第二段：AI 意图识别
        try {
            String aiRaw = maxKbClient.chatWithSystem(INTENT_SYSTEM_PROMPT, message);
            Map<String, Object> aiJson = maxKbClient.tryExtractJson(aiRaw);
            if (aiJson != null) {
                return handleAiIntent(aiJson, message);
            }
        } catch (Exception e) {
            log.warn("AI 意图识别失败，降级为纯问答: {}", e.getMessage());
        }

        // 第三段：纯知识问答
        return AssistantChatResponse.knowledge(maxKbClient.chat(message));
    }

    // ── 第一段：正则阈值 ────────────────────────────────────────────────────

    private Optional<BigDecimal> parseThresholdCommand(String message) {
        Matcher matcher = SET_THRESHOLD_PATTERN.matcher(message);
        if (!matcher.find()) return Optional.empty();
        BigDecimal threshold = new BigDecimal(matcher.group(1));
        if (threshold.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(400, "光照阈值不能小于0");
        }
        return Optional.of(threshold);
    }

    private AssistantChatResponse executeThresholdUpdate(BigDecimal value) {
        LightingPolicy policy = updateLuxLtThreshold(value);
        return AssistantChatResponse.thresholdUpdated(
                "已将光照触发阈值调整为 " + plain(value) + " lux。",
                actionMap("SET_LUX_LT_THRESHOLD", policy, Map.of("luxLt", value)));
    }

    // ── 第二段：AI 意图 ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private AssistantChatResponse handleAiIntent(Map<String, Object> aiJson, String originalMessage) {
        String intent = aiJson.get("intent") != null ? aiJson.get("intent").toString() : "";

        if (!"UPDATE_POLICY".equals(intent)) {
            // 非修改意图，走纯问答
            return AssistantChatResponse.knowledge(maxKbClient.chat(originalMessage));
        }

        Object paramsObj = aiJson.get("params");
        if (!(paramsObj instanceof Map<?, ?> rawParams)) {
            return AssistantChatResponse.knowledge(maxKbClient.chat(originalMessage));
        }

        Map<String, Object> params = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : rawParams.entrySet()) {
            if (e.getKey() instanceof String key && PARAM_WHITELIST.contains(key)) {
                params.put(key, e.getValue());
            }
        }

        if (params.isEmpty()) {
            return AssistantChatResponse.knowledge("已收到修改请求，但未识别到有效的参数。请明确具体要改哪个参数，例如：'把开灯阈值调到35'。");
        }

        // 校验并执行
        validateParams(params);
        LightingPolicy policy = applyParams(params);
        String description = buildResultDescription(params);

        return AssistantChatResponse.thresholdUpdated(description, actionMap(
                "AI_UPDATE_POLICY", policy, params));
    }

    private void validateParams(Map<String, Object> params) {
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();

            if (val == null) {
                throw new BusinessException(400, "参数 " + key + " 不能为空");
            }

            if (NUMERIC_PARAMS.contains(key)) {
                BigDecimal num;
                try {
                    num = new BigDecimal(val.toString());
                } catch (NumberFormatException ex) {
                    throw new BusinessException(400, "参数 " + key + " 不是有效数字: " + val);
                }
                BigDecimal min = PARAM_MIN.get(key);
                BigDecimal max = PARAM_MAX.get(key);
                if (min != null && num.compareTo(min) < 0) {
                    throw new BusinessException(400, key + " 不能小于 " + plain(min));
                }
                if (max != null && num.compareTo(max) > 0) {
                    throw new BusinessException(400, key + " 不能大于 " + plain(max));
                }
            }

            if ("startTime".equals(key) || "endTime".equals(key)) {
                try {
                    LocalTime.parse(val.toString(), DateTimeFormatter.ofPattern("HH:mm"));
                } catch (DateTimeParseException ex) {
                    throw new BusinessException(400, key + " 格式错误，应为 HH:mm，当前值: " + val);
                }
            }

            if ("enabled".equals(key) && !(val instanceof Boolean)) {
                String s = val.toString().toLowerCase();
                if (!"true".equals(s) && !"false".equals(s)) {
                    throw new BusinessException(400, "enabled 必须为 true 或 false，当前值: " + val);
                }
            }
        }
    }

    private LightingPolicy applyParams(Map<String, Object> params) {
        LightingPolicy policy = findLuxThresholdPolicy();
        if (policy == null) {
            throw new BusinessException(404, "光照阈值策略不存在");
        }

        Map<String, Object> conditions = parseConditions(policy.getConditions());
        boolean conditionsChanged = false;

        for (Map.Entry<String, Object> e : params.entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();

            switch (key) {
                case "lux_lt" -> {
                    BigDecimal luxLt = new BigDecimal(val.toString());
                    BigDecimal luxGt = toBigDecimal(conditions.get(CONDITION_LUX_GT));
                    if (luxGt != null && luxLt.compareTo(luxGt) >= 0) {
                        throw new BusinessException(400, "开灯光照阈值必须小于关灯光照阈值");
                    }
                    conditions.put(CONDITION_LUX_LT, new BigDecimal(val.toString()));
                    conditionsChanged = true;
                }
                case "lux_gt" -> {
                    BigDecimal luxGt = new BigDecimal(val.toString());
                    BigDecimal luxLt = toBigDecimal(conditions.get(CONDITION_LUX_LT));
                    if (luxLt != null && luxLt.compareTo(luxGt) >= 0) {
                        throw new BusinessException(400, "关灯光照阈值必须大于开灯光照阈值");
                    }
                    conditions.put(CONDITION_LUX_GT, new BigDecimal(val.toString()));
                    conditionsChanged = true;
                }
                case "temp_lt", "startTime", "endTime" -> {
                    conditions.put(key, val.toString());
                    conditionsChanged = true;
                }
                case "brightness" -> {
                    BigDecimal b = new BigDecimal(val.toString());
                    policy.setAction("DIMMING(" + b.intValue() + ")");
                }
                case "enabled" -> {
                    boolean b = val instanceof Boolean bv ? bv : "true".equalsIgnoreCase(val.toString());
                    policy.setEnabled(b);
                }
            }
        }

        if (conditionsChanged) {
            try {
                policy.setConditions(objectMapper.writeValueAsString(conditions));
            } catch (Exception e) {
                throw new BusinessException(500, "策略条件 JSON 序列化失败");
            }
        }

        lightingPolicyService.updateById(policy);
        return policy;
    }

    private String buildResultDescription(Map<String, Object> params) {
        List<String> items = new ArrayList<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            items.add(paramLabel(e.getKey()) + " → " + formatVal(e.getKey(), e.getValue()));
        }
        return "已按 AI 建议调整策略: " + String.join("，", items);
    }

    private String paramLabel(String key) {
        return switch (key) {
            case "lux_lt" -> "开灯阈值";
            case "lux_gt" -> "关灯阈值";
            case "temp_lt" -> "低温触发";
            case "startTime" -> "开始时间";
            case "endTime" -> "结束时间";
            case "brightness" -> "调光亮度";
            case "enabled" -> "启用状态";
            default -> key;
        };
    }

    private String formatVal(String key, Object val) {
        return switch (key) {
            case "lux_lt", "lux_gt" -> plain(new BigDecimal(val.toString())) + " lux";
            case "temp_lt" -> plain(new BigDecimal(val.toString())) + "℃";
            case "brightness" -> plain(new BigDecimal(val.toString())) + "%";
            default -> val.toString();
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> actionMap(String name, LightingPolicy policy, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("policyId", policy.getId());
        m.put("policyName", policy.getName());
        m.putAll(extra);
        return m;
    }

    // ── 策略匹配 ────────────────────────────────────────────────────────────

    private LightingPolicy updateLuxLtThreshold(BigDecimal luxLt) {
        LightingPolicy policy = findLuxThresholdPolicy();
        if (policy == null) throw new BusinessException(404, "光照阈值策略不存在");

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
        List<LightingPolicy> candidates = lightingPolicyService.list().stream()
                .filter(p -> !Boolean.TRUE.equals(p.getDeleted()))
                .filter(p -> hasLuxCondition(p.getConditions()))
                .sorted((a, b) -> Integer.compare(priority(a), priority(b)))
                .toList();

        if (candidates.isEmpty()) return null;

        // 优先选 THRESHOLD 类型，否则取优先级最高的
        return candidates.stream()
                .filter(p -> "THRESHOLD".equals(p.getPolicyType()))
                .findFirst()
                .orElse(candidates.get(0));
    }

    private int priority(LightingPolicy policy) {
        return policy.getPriority() != null ? policy.getPriority() : Integer.MAX_VALUE;
    }

    private boolean hasLuxCondition(String conditions) {
        if (conditions == null || conditions.isBlank()) return false;
        Map<String, Object> parsed = parseConditions(conditions);
        return parsed.containsKey(CONDITION_LUX_LT) || parsed.containsKey(CONDITION_LUX_GT);
    }

    // ── JSON 解析 ──────────────────────────────────────────────────────────

    private Map<String, Object> parseConditions(String conditionsJson) {
        if (conditionsJson == null || conditionsJson.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(conditionsJson,
                    new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new BusinessException(500, "策略条件 JSON 格式错误");
        }
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
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

        List<ControlCommand> cmds = controlCommandMapper.selectList(
                new LambdaQueryWrapper<ControlCommand>().eq(ControlCommand::getDeviceId, deviceId)
                        .ge(ControlCommand::getIssuedAt, java.time.LocalDateTime.now().minusDays(7)));
        long acked = cmds.stream().filter(c -> c.getAckAt() != null).count();
        ctx.append("指令响应率: ").append(cmds.isEmpty() ? "无记录" :
                Math.round(100.0 * acked / cmds.size()) + "% (" + acked + "/" + cmds.size() + ")").append("\n");

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
