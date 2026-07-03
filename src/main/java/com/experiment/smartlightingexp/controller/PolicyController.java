package com.experiment.smartlightingexp.controller;

import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.common.SecurityContext;
import com.experiment.smartlightingexp.dto.LuxThresholdRequest;
import com.experiment.smartlightingexp.dto.LuxThresholdResponse;
import com.experiment.smartlightingexp.dto.PolicyQueryRequest;
import com.experiment.smartlightingexp.dto.PolicyRequest;
import com.experiment.smartlightingexp.entity.AuditLog;
import com.experiment.smartlightingexp.entity.LightingPolicy;
import com.experiment.smartlightingexp.mapper.AuditLogMapper;
import com.experiment.smartlightingexp.service.LightingPolicyService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 照明策略控制器 — 策略的增删改查与启停管理。
 * 所有写操作记录审计日志，满足 IR-11 安全可信控制的可追溯要求。
 */
@Slf4j
@RestController
@RequestMapping("/api/policies")
@RequiredArgsConstructor
public class PolicyController {

    private static final String POLICY_TYPE_THRESHOLD = "THRESHOLD";
    private static final String CONDITION_LUX_LT = "lux_lt";
    private static final String CONDITION_LUX_GT = "lux_gt";

    private final LightingPolicyService lightingPolicyService;
    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    /**
     * 查询所有策略（未删除）。
     */
    @GetMapping
    public Result<List<LightingPolicy>> list() {
        List<LightingPolicy> list = lightingPolicyService.lambdaQuery()
                .eq(LightingPolicy::getDeleted, false)
                .orderByAsc(LightingPolicy::getPriority)
                .list();
        return Result.success(list);
    }

    /**
     * 组合条件分页查询策略列表。
     * 支持按名称模糊、策略类型、启用状态、优先级范围、生效时段筛选。
     */
    @PostMapping("/list")
    public Result<IPage<LightingPolicy>> listPage(@RequestBody PolicyQueryRequest request) {
        LambdaQueryWrapper<LightingPolicy> wrapper = new LambdaQueryWrapper<>();

        // 默认只查未删除的策略
        wrapper.eq(LightingPolicy::getDeleted, false);

        if (request.getName() != null && !request.getName().isBlank()) {
            wrapper.like(LightingPolicy::getName, request.getName());
        }
        if (request.getPolicyType() != null && !request.getPolicyType().isBlank()) {
            wrapper.eq(LightingPolicy::getPolicyType, request.getPolicyType());
        }
        if (request.getEnabled() != null) {
            wrapper.eq(LightingPolicy::getEnabled, request.getEnabled());
        }
        if (request.getPriorityMin() != null) {
            wrapper.ge(LightingPolicy::getPriority, request.getPriorityMin());
        }
        if (request.getPriorityMax() != null) {
            wrapper.le(LightingPolicy::getPriority, request.getPriorityMax());
        }
        if (request.getEffectiveTime() != null && !request.getEffectiveTime().isBlank()) {
            wrapper.like(LightingPolicy::getEffectiveTime, request.getEffectiveTime());
        }

        wrapper.orderByAsc(LightingPolicy::getPriority);

        Page<LightingPolicy> page = new Page<>(request.getPage(), request.getSize());
        IPage<LightingPolicy> result = lightingPolicyService.page(page, wrapper);

        log.info("[策略查询] 条件: name={}, policyType={}, enabled={}, 结果数={}",
                request.getName(), request.getPolicyType(), request.getEnabled(),
                result.getRecords().size());
        return Result.success(result);
    }

    /**
     * 查询当前光照阈值配置。
     */
    @GetMapping("/lux-threshold")
    public Result<LuxThresholdResponse> getLuxThreshold() {
        LightingPolicy policy = findLuxThresholdPolicy();
        if (policy == null) {
            return Result.error(404, "光照阈值策略不存在");
        }

        try {
            Map<String, Object> conditions = parseConditions(policy.getConditions());
            return Result.success(buildLuxThresholdResponse(policy, conditions));
        } catch (JsonProcessingException e) {
            log.error("[策略] 光照阈值条件 JSON 解析失败: id={}, conditions={}",
                    policy.getId(), policy.getConditions(), e);
            return Result.error(500, "策略条件 JSON 格式错误");
        }
    }

    /**
     * 更新光照阈值配置。
     */
    @PutMapping("/lux-threshold")
    public Result<LuxThresholdResponse> updateLuxThreshold(@Valid @RequestBody LuxThresholdRequest request,
                                                           HttpServletRequest httpRequest) {
        if (request.getLuxLt().compareTo(request.getLuxGt()) >= 0) {
            saveAuditLog("THRESHOLD_SET", "THRESHOLD", "-",
                    "光照阈值参数非法-设置失败", "FAIL", httpRequest);
            return Result.error(400, "开灯光照阈值必须小于关灯光照阈值");
        }

        LightingPolicy policy = findLuxThresholdPolicy();
        if (policy == null) {
            saveAuditLog("THRESHOLD_SET", "THRESHOLD", "-",
                    "光照阈值策略不存在-设置失败", "FAIL", httpRequest);
            return Result.error(404, "光照阈值策略不存在");
        }

        try {
            Map<String, Object> conditions = parseConditions(policy.getConditions());
            conditions.put(CONDITION_LUX_LT, request.getLuxLt());
            conditions.put(CONDITION_LUX_GT, request.getLuxGt());

            String conditionsJson = objectMapper.writeValueAsString(conditions);
            policy.setConditions(conditionsJson);
            lightingPolicyService.updateById(policy);

            saveAuditLog("THRESHOLD_SET", "THRESHOLD", String.valueOf(policy.getId()),
                    "设置光照阈值-lux_lt=" + request.getLuxLt().toPlainString()
                            + ",lux_gt=" + request.getLuxGt().toPlainString(),
                    "SUCCESS", httpRequest);
            log.info("[策略] 光照阈值更新: policyId={}, lux_lt={}, lux_gt={}",
                    policy.getId(), request.getLuxLt(), request.getLuxGt());
            return Result.success(buildLuxThresholdResponse(policy, conditions));
        } catch (JsonProcessingException e) {
            saveAuditLog("THRESHOLD_SET", "THRESHOLD", String.valueOf(policy.getId()),
                    "策略条件JSON格式错误-设置失败", "FAIL", httpRequest);
            log.error("[策略] 光照阈值条件 JSON 处理失败: id={}, conditions={}",
                    policy.getId(), policy.getConditions(), e);
            return Result.error(500, "策略条件 JSON 格式错误");
        }
    }

    /**
     * 查询单个策略详情。
     */
    @GetMapping("/{id}")
    public Result<LightingPolicy> getById(@PathVariable Long id) {
        LightingPolicy policy = lightingPolicyService.getById(id);
        if (policy == null || Boolean.TRUE.equals(policy.getDeleted())) {
            return Result.error("策略不存在");
        }
        return Result.success(policy);
    }

    /**
     * 新增策略。
     */
    @PostMapping
    public Result<Void> create(@Valid @RequestBody PolicyRequest request,
                               HttpServletRequest httpRequest) {
        LightingPolicy policy = new LightingPolicy();
        policy.setName(request.getName());
        policy.setPolicyType(request.getPolicyType());
        policy.setConditions(request.getConditions());
        policy.setAction(request.getAction());
        policy.setPriority(request.getPriority() != null ? request.getPriority() : 100);
        policy.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        policy.setDeleted(false);
        policy.setEffectiveTime(request.getEffectiveTime());
        lightingPolicyService.save(policy);

        saveAuditLog("POLICY_CREATE", "POLICY", String.valueOf(policy.getId()),
                "新增策略-" + request.getName(), "SUCCESS", httpRequest);
        log.info("[策略] 新增: id={}, name={}", policy.getId(), request.getName());
        return Result.success();
    }

    /**
     * 更新策略。
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody PolicyRequest request,
                               HttpServletRequest httpRequest) {
        LightingPolicy existing = lightingPolicyService.getById(id);
        if (existing == null || Boolean.TRUE.equals(existing.getDeleted())) {
            saveAuditLog("POLICY_UPDATE", "POLICY", String.valueOf(id),
                    "策略不存在-更新失败", "FAIL", httpRequest);
            return Result.error("策略不存在");
        }

        existing.setName(request.getName());
        existing.setPolicyType(request.getPolicyType());
        existing.setConditions(request.getConditions());
        existing.setAction(request.getAction());
        existing.setPriority(request.getPriority() != null ? request.getPriority() : 100);
        existing.setEnabled(request.getEnabled() != null ? request.getEnabled() : existing.getEnabled());
        existing.setEffectiveTime(request.getEffectiveTime());
        lightingPolicyService.updateById(existing);

        saveAuditLog("POLICY_UPDATE", "POLICY", String.valueOf(id),
                "更新策略-" + request.getName(), "SUCCESS", httpRequest);
        log.info("[策略] 更新: id={}, name={}", id, request.getName());
        return Result.success();
    }

    /**
     * 删除策略（软删除）。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               HttpServletRequest httpRequest) {
        LightingPolicy existing = lightingPolicyService.getById(id);
        if (existing == null || Boolean.TRUE.equals(existing.getDeleted())) {
            saveAuditLog("POLICY_DELETE", "POLICY", String.valueOf(id),
                    "策略不存在-删除失败", "FAIL", httpRequest);
            return Result.error("策略不存在");
        }

        existing.setDeleted(true);
        lightingPolicyService.updateById(existing);

        saveAuditLog("POLICY_DELETE", "POLICY", String.valueOf(id),
                "删除策略-" + existing.getName(), "SUCCESS", httpRequest);
        log.info("[策略] 删除: id={}, name={}", id, existing.getName());
        return Result.success();
    }

    /**
     * 启用/禁用策略。
     */
    @PutMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable Long id,
                               HttpServletRequest httpRequest) {
        LightingPolicy existing = lightingPolicyService.getById(id);
        if (existing == null || Boolean.TRUE.equals(existing.getDeleted())) {
            saveAuditLog("POLICY_TOGGLE", "POLICY", String.valueOf(id),
                    "策略不存在-切换失败", "FAIL", httpRequest);
            return Result.error("策略不存在");
        }

        boolean newEnabled = !Boolean.TRUE.equals(existing.getEnabled());
        existing.setEnabled(newEnabled);
        lightingPolicyService.updateById(existing);

        saveAuditLog("POLICY_TOGGLE", "POLICY", String.valueOf(id),
                (newEnabled ? "启用" : "禁用") + "策略-" + existing.getName(), "SUCCESS", httpRequest);
        log.info("[策略] 切换: id={}, enabled={}", id, newEnabled);
        return Result.success();
    }

    // ======================== 光照阈值 ========================

    private LightingPolicy findLuxThresholdPolicy() {
        List<LightingPolicy> policies = lightingPolicyService.lambdaQuery()
                .eq(LightingPolicy::getPolicyType, POLICY_TYPE_THRESHOLD)
                .eq(LightingPolicy::getDeleted, false)
                .orderByAsc(LightingPolicy::getPriority)
                .list();
        if (policies.isEmpty()) {
            return null;
        }

        return policies.stream()
                .filter(policy -> hasLuxCondition(policy.getConditions()))
                .findFirst()
                .orElse(policies.get(0));
    }

    private boolean hasLuxCondition(String conditions) {
        return conditions != null
                && (conditions.contains("\"" + CONDITION_LUX_LT + "\"")
                || conditions.contains("\"" + CONDITION_LUX_GT + "\""));
    }

    private Map<String, Object> parseConditions(String conditionsJson) throws JsonProcessingException {
        if (conditionsJson == null || conditionsJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.readValue(conditionsJson, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private LuxThresholdResponse buildLuxThresholdResponse(LightingPolicy policy, Map<String, Object> conditions) {
        LuxThresholdResponse response = new LuxThresholdResponse();
        response.setPolicyId(policy.getId());
        response.setPolicyName(policy.getName());
        response.setLuxLt(toBigDecimal(conditions.get(CONDITION_LUX_LT)));
        response.setLuxGt(toBigDecimal(conditions.get(CONDITION_LUX_GT)));
        response.setConditions(policy.getConditions());
        response.setEnabled(policy.getEnabled());
        response.setPriority(policy.getPriority());
        return response;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ======================== 审计日志 ========================

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
            auditLogMapper.insert(logEntry);
        } catch (Exception e) {
            log.error("审计日志记录失败: {}", e.getMessage());
        }
    }

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
