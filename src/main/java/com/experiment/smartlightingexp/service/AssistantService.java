package com.experiment.smartlightingexp.service;

import com.experiment.smartlightingexp.common.BusinessException;
import com.experiment.smartlightingexp.dto.AssistantChatResponse;
import com.experiment.smartlightingexp.entity.LightingPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
