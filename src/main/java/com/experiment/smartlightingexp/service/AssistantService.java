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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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

    // ── 纯问答轻量提示词（仅 60 字符，给 MaxKB 领域上下文 + 简洁约束） ───
    private static final String QA_SYSTEM_PROMPT = """
你是智慧路灯运维助手，专精路灯故障排查与维修。回答要求：简洁，3-5条要点，不超300字。""";

    // ── 本地关键词预筛（判断是否为参数修改命令） ─────────────────────────
    // 只有命中这些词才携带大型 System Prompt 调用 MaxKB，
    // 纯知识问答类消息用轻量 Prompt，省掉 Token 开销
    private static final Pattern PARAM_COMMAND_PATTERN = Pattern.compile(
            "调到|调整到|修改|更改|设置|设为|改成|改为|调高|调低|调亮|调暗|"
            + "阈值|亮度|调光|关灯|开灯|启用|停用|"
            + "温度触发|开始时间|结束时间|时段|lux|策略参数");

    private boolean looksLikeParamCommand(String message) {
        return PARAM_COMMAND_PATTERN.matcher(message).find();
    }

    // ── AI 意图识别 + 知识问答（合并为一次调用） ─────────────────────────────
    private static final String COMBINED_SYSTEM_PROMPT = """
你是一个智慧路灯系统的运维助手。你可以帮助用户解答运维知识问题，也可以帮助用户修改照明策略参数。

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

如果用户意图是修改上述策略参数，请确认修改并给出操作说明，然后在回答末尾附加一个 JSON 代码块：
```json
{"intent":"UPDATE_POLICY","params":{"lux_lt":30}}
```
params 只包含要修改的参数。注意：JSON 必须用 ```json ``` 包裹。

如果用户只是咨询、闲聊或询问运维知识，则正常回答问题，**不要**附加任何 JSON。
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

    // ── 响应缓存（减少重复请求的 MaxKB 等待时间） ─────────────────────────
    private static final int CACHE_MAX_SIZE = 200;
    private static final long CACHE_TTL_MS = 2 * 60 * 1000; // 2 分钟
    private final ConcurrentHashMap<String, CacheEntry> responseCache = new ConcurrentHashMap<>();

    private record CacheEntry(AssistantChatResponse response, long expireAt) {}

    private AssistantChatResponse getCached(String key) {
        CacheEntry entry = responseCache.get(key);
        if (entry != null && System.currentTimeMillis() < entry.expireAt()) {
            // 清理过期 entry（惰性清理，不阻塞读）
            if (responseCache.size() > CACHE_MAX_SIZE) {
                responseCache.entrySet().removeIf(e -> System.currentTimeMillis() > e.getValue().expireAt());
            }
            return entry.response();
        }
        responseCache.remove(key);
        return null;
    }

    private void putCache(String key, AssistantChatResponse response) {
        if (responseCache.size() >= CACHE_MAX_SIZE) {
            responseCache.entrySet().removeIf(e -> System.currentTimeMillis() > e.getValue().expireAt());
        }
        if (responseCache.size() < CACHE_MAX_SIZE) {
            responseCache.put(key, new CacheEntry(response, System.currentTimeMillis() + CACHE_TTL_MS));
        }
    }

    private String cacheKey(String... parts) {
        return String.join("::", parts);
    }

    // ── 主入口 ──────────────────────────────────────────────────────────────

    public AssistantChatResponse chat(String rawMessage) {
        String message = rawMessage.trim();

        // 第一段：本地正则快速路径（纯阈值命令，毫秒级，不缓存）
        Optional<BigDecimal> threshold = parseThresholdCommand(message);
        if (threshold.isPresent()) {
            return executeThresholdUpdate(threshold.get());
        }

        // 第二段：查缓存（2 分钟 TTL，重复问题即时返回）
        String cacheKey = cacheKey("chat", message);
        AssistantChatResponse cached = getCached(cacheKey);
        if (cached != null) {
            return cached;
        }

        AssistantChatResponse response;

        // 第三段：本地关键词预筛
        if (looksLikeParamCommand(message)) {
            // 参数修改意图 → 带 System Prompt 调用 MaxKB
            String aiRaw = maxKbClient.chatWithSystem(COMBINED_SYSTEM_PROMPT, message);
            Map<String, Object> aiJson = maxKbClient.tryExtractJson(aiRaw);
            if (aiJson != null && "UPDATE_POLICY".equals(aiJson.get("intent"))) {
                try {
                    response = handleAiIntent(aiJson, message);
                } catch (BusinessException e) {
                    response = AssistantChatResponse.knowledge(
                            aiRaw + "\n\n---\n系统未能执行参数修改: " + e.getMessage());
                }
            } else {
                response = AssistantChatResponse.knowledge(aiRaw);
            }
        } else {
            // 第四段：纯知识问答 — 轻量 System Prompt（领域上下文 + 简洁约束）
            response = AssistantChatResponse.knowledge(
                    maxKbClient.chatWithSystem(QA_SYSTEM_PROMPT, message));
        }

        putCache(cacheKey, response);
        return response;
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
        Object paramsObj = aiJson.get("params");
        if (!(paramsObj instanceof Map<?, ?> rawParams)) {
            throw new BusinessException(400, "AI 返回了 UPDATE_POLICY 意图但未包含有效的 params");
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

        boolean hasQuestion = question != null && !question.isBlank();

        // 无具体问题 → 本地快速诊断（秒级），不调用 MaxKB
        if (!hasQuestion) {
            return buildLocalDiagnosis(device);
        }

        // 有具体问题 → 带缓存调 MaxKB
        String cacheKey = cacheKey("diag", deviceId, question);
        AssistantChatResponse cached = getCached(cacheKey);
        if (cached != null) {
            return cached;
        }

        String prompt = buildCompactDiagnosisPrompt(device, question);
        AssistantChatResponse response;
        try {
            response = AssistantChatResponse.knowledge(maxKbClient.chat(prompt));
        } catch (Exception e) {
            response = buildLocalDiagnosis(device);
        }
        putCache(cacheKey, response);
        return response;
    }

    /** 本地快速诊断 — 基于规则的即时评估，不调用 MaxKB */
    private AssistantChatResponse buildLocalDiagnosis(Device device) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(device.getDeviceId());
        if (device.getName() != null) sb.append(" ").append(device.getName());
        sb.append(" ===\n\n");

        // 1. 健康评分
        int score = device.getHealthScore() != null ? device.getHealthScore().intValue() : 0;
        String level = score >= 90 ? "优秀" : score >= 70 ? "良好" : score >= 50 ? "一般" : score >= 30 ? "较差" : "危险";
        sb.append("健康评分: ").append(score).append(" (").append(level).append(")\n");

        // 2. 在线状态
        sb.append("设备状态: ").append(statusLabel(device.getStatus())).append("\n");

        // 3. 最近告警
        List<AlarmRecord> alarms = alarmRecordService.list(
                new LambdaQueryWrapper<AlarmRecord>().eq(AlarmRecord::getDeviceId, device.getDeviceId())
                        .orderByDesc(AlarmRecord::getStartAt).last("LIMIT 5"));
        long activeAlarmCount = alarms.stream().filter(a -> "ACTIVE".equals(a.getStatus())).count();
        sb.append("活跃告警: ").append(activeAlarmCount).append(" 条\n");
        if (!alarms.isEmpty()) {
            for (AlarmRecord a : alarms) {
                sb.append("  - ").append(alarmLabel(a.getType()))
                        .append(" (").append(a.getLevel()).append(")")
                        .append(" | ").append(a.getStartAt());
                if ("RESOLVED".equals(a.getStatus())) sb.append(" | 已恢复");
                sb.append("\n");
            }
        }

        // 4. 指令响应率
        List<ControlCommand> cmds = controlCommandMapper.selectList(
                new LambdaQueryWrapper<ControlCommand>().eq(ControlCommand::getDeviceId, device.getDeviceId())
                        .ge(ControlCommand::getIssuedAt, java.time.LocalDateTime.now().minusDays(7)));
        long acked = cmds.stream().filter(c -> c.getAckAt() != null).count();
        String respRate = cmds.isEmpty() ? "无记录" :
                Math.round(100.0 * acked / cmds.size()) + "% (" + acked + "/" + cmds.size() + ")";
        sb.append("指令响应率: ").append(respRate).append("\n");

        // 5. 最新遥测摘要
        if (device.getLatestData() != null && !device.getLatestData().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> tel = objectMapper.readValue(device.getLatestData(), Map.class);
                sb.append("最新遥测: ");
                sb.append("光照=").append(tel.getOrDefault("illuminance", "?")).append("lux, ");
                sb.append("温度=").append(tel.getOrDefault("temperature", "?")).append("℃, ");
                sb.append("AQI=").append(tel.getOrDefault("aqi", "?")).append("\n");
            } catch (Exception ignored) {}
        }

        // 6. 规则评估
        sb.append("\n---\n");
        if (score >= 90 && device.getStatus() != null && device.getStatus() == 1) {
            sb.append("设备运行正常，无需干预。");
        } else if (device.getStatus() != null && device.getStatus() == 2) {
            sb.append("设备离线，建议检查供电和网络连接。");
        } else if (score < 50) {
            sb.append("健康分偏低，建议安排检修。可输入具体问题获取 AI 深度分析（例：\"检查通信模块\"）。");
        } else if (activeAlarmCount > 0) {
            sb.append("存在活跃告警，请查看告警详情。输入具体问题可获取 AI 维修建议。");
        } else {
            sb.append("如需 AI 深度分析，请在输入框中描述具体问题。");
        }

        return AssistantChatResponse.knowledge(sb.toString());
    }

    /** 精简版诊断 Prompt — 比原文减少约 40% Token */
    private String buildCompactDiagnosisPrompt(Device device, String question) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("智慧路灯维修。设备:").append(device.getDeviceId())
                .append(" ").append(device.getName() != null ? device.getName() : "")
                .append(", 区域:").append(device.getArea() != null ? device.getArea() : "?")
                .append(", 健康分:").append(device.getHealthScore() != null ? device.getHealthScore() : "?")
                .append(", 状态:").append(statusLabel(device.getStatus())).append("\n");

        List<AlarmRecord> alarms = alarmRecordService.list(
                new LambdaQueryWrapper<AlarmRecord>().eq(AlarmRecord::getDeviceId, device.getDeviceId())
                        .orderByDesc(AlarmRecord::getStartAt).last("LIMIT 3"));
        if (!alarms.isEmpty()) {
            ctx.append("告警:");
            for (AlarmRecord a : alarms) {
                ctx.append(" ").append(alarmLabel(a.getType())).append("(").append(a.getStatus()).append(")");
            }
            ctx.append("\n");
        }

        List<ControlCommand> cmds = controlCommandMapper.selectList(
                new LambdaQueryWrapper<ControlCommand>().eq(ControlCommand::getDeviceId, device.getDeviceId())
                        .ge(ControlCommand::getIssuedAt, java.time.LocalDateTime.now().minusDays(7)));
        long acked = cmds.stream().filter(c -> c.getAckAt() != null).count();
        ctx.append("指令ACK率:").append(cmds.isEmpty() ? "?" :
                Math.round(100.0 * acked / cmds.size()) + "%").append("\n");

        if (device.getLatestData() != null && !device.getLatestData().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> tel = objectMapper.readValue(device.getLatestData(), Map.class);
                ctx.append("遥测: lux=").append(tel.getOrDefault("illuminance", "?"))
                        .append(" temp=").append(tel.getOrDefault("temperature", "?"))
                        .append("℃\n");
            } catch (Exception ignored) {}
        }

        ctx.append("问题: ").append(question).append("\n请简洁回答。");
        return ctx.toString();
    }

    private String statusLabel(Integer s) {
        return switch (s != null ? s : 0) {
            case 1 -> "在线";
            case 2 -> "离线";
            case 3 -> "异常";
            default -> "停用";
        };
    }

    private String alarmLabel(String type) {
        return switch (type != null ? type : "") {
            case "OFFLINE" -> "离线";
            case "FAULT" -> "故障";
            case "HEALTH_LOW" -> "健康分低";
            default -> type;
        };
    }

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
