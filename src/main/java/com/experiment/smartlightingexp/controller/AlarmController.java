package com.experiment.smartlightingexp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.experiment.smartlightingexp.common.RequirePermission;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.common.SecurityContext;
import com.experiment.smartlightingexp.dto.AlarmQueryRequest;
import com.experiment.smartlightingexp.entity.AlarmRecord;
import com.experiment.smartlightingexp.entity.AuditLog;
import com.experiment.smartlightingexp.mapper.AlarmRecordMapper;
import com.experiment.smartlightingexp.mapper.AuditLogMapper;
import com.experiment.smartlightingexp.service.AlarmRecordService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 告警记录控制器 — 告警增删改查 + 处理确认 + 统计趋势 + 批量操作。
 * 满足 FR-09 告警日志查看需求，IR-08 大屏数据支持，IR-11 安全审计。
 */
@Slf4j
@RestController
@RequestMapping("/api/alarms")
@RequiredArgsConstructor
public class AlarmController {

    private final AlarmRecordService alarmRecordService;
    private final AlarmRecordMapper alarmRecordMapper;
    private final AuditLogMapper auditLogMapper;

    // ======================== 查询 ========================

    /**
     * 组合条件查询告警列表（分页）。
     */
    @PostMapping("/list")
    public Result<IPage<AlarmRecord>> list(@RequestBody AlarmQueryRequest request) {
        LambdaQueryWrapper<AlarmRecord> wrapper = buildQueryWrapper(request);
        Page<AlarmRecord> page = new Page<>(request.getPage(), request.getSize());
        IPage<AlarmRecord> result = alarmRecordService.page(page, wrapper);

        log.info("[告警查询] 条件: deviceId={}, type={}, level={}, status={}, 结果数={}",
                request.getDeviceId(), request.getType(), request.getLevel(),
                request.getStatus(), result.getRecords().size());
        return Result.success(result);
    }

    /**
     * 查询单条告警详情。
     */
    @GetMapping("/{id}")
    public Result<AlarmRecord> getById(@PathVariable Long id) {
        AlarmRecord record = alarmRecordService.getById(id);
        if (record == null) {
            return Result.error("告警记录不存在");
        }
        return Result.success(record);
    }

    // ======================== 新增 ========================

    /**
     * 新增告警（手动创建）。
     */
    @PostMapping
    public Result<Void> create(@RequestBody AlarmRecord alarm,
                               HttpServletRequest httpRequest) {
        if (alarm.getDeviceId() == null || alarm.getDeviceId().isBlank()) {
            return Result.error("设备ID不能为空");
        }
        if (alarm.getType() == null || alarm.getType().isBlank()) {
            return Result.error("告警类型不能为空");
        }
        if (alarm.getStartAt() == null) {
            alarm.setStartAt(LocalDateTime.now());
        }
        if (alarm.getStatus() == null) {
            alarm.setStatus("ACTIVE");
        }
        alarmRecordService.save(alarm);

        saveAuditLog("ALARM_CREATE", "ALARM", String.valueOf(alarm.getId()),
                "新增告警-" + alarm.getType() + ":" + alarm.getReason(), "SUCCESS", httpRequest);
        log.info("[告警] 新增: id={}, deviceId={}, type={}", alarm.getId(), alarm.getDeviceId(), alarm.getType());
        return Result.success();
    }

    // ======================== 修改 ========================

    /**
     * 更新告警（修改状态、处理人、恢复时间等）。
     */
    @RequirePermission("alarm:handle")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id,
                               @RequestBody AlarmRecord alarm,
                               HttpServletRequest httpRequest) {
        AlarmRecord existing = alarmRecordService.getById(id);
        if (existing == null) {
            saveAuditLog("ALARM_UPDATE", "ALARM", String.valueOf(id),
                    "告警不存在-更新失败", "FAIL", httpRequest);
            return Result.error("告警记录不存在");
        }

        if (alarm.getStatus() != null) existing.setStatus(alarm.getStatus());
        if (alarm.getLevel() != null) existing.setLevel(alarm.getLevel());
        if (alarm.getReason() != null) existing.setReason(alarm.getReason());
        if (alarm.getRecoverAt() != null) existing.setRecoverAt(alarm.getRecoverAt());
        if (alarm.getHandler() != null) existing.setHandler(alarm.getHandler());
        alarmRecordService.updateById(existing);

        saveAuditLog("ALARM_UPDATE", "ALARM", String.valueOf(id),
                "更新告警-status:" + existing.getStatus(), "SUCCESS", httpRequest);
        log.info("[告警] 更新: id={}, status={}, handler={}", id, existing.getStatus(), existing.getHandler());
        return Result.success();
    }

    // ======================== 删除 ========================

    /**
     * 删除单条告警。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               HttpServletRequest httpRequest) {
        AlarmRecord existing = alarmRecordService.getById(id);
        if (existing == null) {
            saveAuditLog("ALARM_DELETE", "ALARM", String.valueOf(id),
                    "告警不存在-删除失败", "FAIL", httpRequest);
            return Result.error("告警记录不存在");
        }

        alarmRecordService.removeById(id);
        saveAuditLog("ALARM_DELETE", "ALARM", String.valueOf(id),
                "删除告警-" + existing.getType() + ":" + existing.getReason(), "SUCCESS", httpRequest);
        log.info("[告警] 删除: id={}, deviceId={}", id, existing.getDeviceId());
        return Result.success();
    }

    // ======================== 告警处理/确认 ========================

    /**
     * 处理/确认告警：设置处理人、恢复时间和备注。
     */
    @RequirePermission("alarm:handle")
    @PutMapping("/{id}/handle")
    public Result<Void> handle(@PathVariable Long id,
                               @RequestBody Map<String, String> body,
                               HttpServletRequest httpRequest) {
        AlarmRecord existing = alarmRecordService.getById(id);
        if (existing == null) {
            return Result.error("告警记录不存在");
        }

        String handler = SecurityContext.getCurrentUsername();
        existing.setHandler(handler);
        existing.setStatus("ACKNOWLEDGED");
        existing.setRecoverAt(LocalDateTime.now());

        String remark = body != null ? body.getOrDefault("remark", "") : "";
        alarmRecordService.updateById(existing);

        String detail = "处理告警-" + existing.getType()
                + (remark.isEmpty() ? "" : ", 备注:" + remark);
        saveAuditLog("ALARM_HANDLE", "ALARM", String.valueOf(id),
                detail, "SUCCESS", httpRequest);
        log.info("[告警] 处理: id={}, handler={}, remark={}", id, handler, remark);
        return Result.success();
    }

    // ======================== 批量操作 ========================

    /**
     * 批量处理/确认告警。
     */
    @RequirePermission("alarm:handle")
    @PutMapping("/batch/handle")
    public Result<Void> batchHandle(@RequestBody Map<String, Object> body,
                                    HttpServletRequest httpRequest) {
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) body.get("ids");
        if (ids == null || ids.isEmpty()) {
            return Result.error("告警ID列表不能为空");
        }

        String handler = SecurityContext.getCurrentUsername();
        LocalDateTime now = LocalDateTime.now();
        List<AlarmRecord> list = alarmRecordService.listByIds(
                ids.stream().map(Long::valueOf).collect(Collectors.toList()));

        for (AlarmRecord alarm : list) {
            alarm.setHandler(handler);
            alarm.setStatus("ACKNOWLEDGED");
            alarm.setRecoverAt(now);
        }
        alarmRecordService.updateBatchById(list);

        saveAuditLog("ALARM_BATCH_HANDLE", "ALARM",
                ids.stream().map(String::valueOf).collect(Collectors.joining(",")),
                "批量处理告警-" + list.size() + "条", "SUCCESS", httpRequest);
        log.info("[告警] 批量处理: count={}", list.size());
        return Result.success();
    }

    /**
     * 批量删除告警。
     */
    @RequirePermission("alarm:delete")
    @DeleteMapping("/batch")
    public Result<Void> batchDelete(@RequestBody Map<String, Object> body,
                                    HttpServletRequest httpRequest) {
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) body.get("ids");
        if (ids == null || ids.isEmpty()) {
            return Result.error("告警ID列表不能为空");
        }

        List<Long> longIds = ids.stream().map(Long::valueOf).collect(Collectors.toList());
        alarmRecordService.removeBatchByIds(longIds);

        saveAuditLog("ALARM_BATCH_DELETE", "ALARM",
                ids.stream().map(String::valueOf).collect(Collectors.joining(",")),
                "批量删除告警-" + ids.size() + "条", "SUCCESS", httpRequest);
        log.info("[告警] 批量删除: count={}", ids.size());
        return Result.success();
    }

    // ======================== 统计与趋势 ========================

    /**
     * 告警统计：按级别、类型、状态分别统计数量。
     * 用于大屏展示（满足 IR-08 数字孪生可视化大屏）。
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        // 按级别统计（ACTIVE）
        List<Map<String, Object>> byLevel = alarmRecordMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AlarmRecord>()
                        .select("level, COUNT(*) as count")
                        .eq("status", "ACTIVE")
                        .groupBy("level"));

        // 按类型统计（ACTIVE）
        List<Map<String, Object>> byType = alarmRecordMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AlarmRecord>()
                        .select("type, COUNT(*) as count")
                        .eq("status", "ACTIVE")
                        .groupBy("type"));

        // 按状态统计
        List<Map<String, Object>> byStatus = alarmRecordMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AlarmRecord>()
                        .select("status, COUNT(*) as count")
                        .groupBy("status"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalActive", alarmRecordMapper.selectCount(
                new LambdaQueryWrapper<AlarmRecord>().eq(AlarmRecord::getStatus, "ACTIVE")));
        result.put("byLevel", buildCountMapV2(byLevel, "level"));
        result.put("byType", buildCountMapV2(byType, "type"));
        result.put("byStatus", buildCountMapV2(byStatus, "status"));
        return Result.success(result);
    }

    /**
     * 告警趋势：按天统计告警发生数量。
     *
     * @param days 最近 N 天，默认 7 天
     */
    @GetMapping("/trend")
    public Result<List<Map<String, Object>>> trend(
            @RequestParam(defaultValue = "7") int days) {
        LocalDate from = LocalDate.now().minusDays(days - 1);
        LocalDateTime fromTime = from.atStartOfDay();

        List<Map<String, Object>> rawList = alarmRecordMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AlarmRecord>()
                        .select("DATE(start_at) as date, COUNT(*) as count")
                        .ge("start_at", fromTime)
                        .groupBy("DATE(start_at)")
                        .orderByAsc("DATE(start_at)"));

        // 填充无数据的日期，保证返回连续 N 天
        Map<String, Long> dateCountMap = new LinkedHashMap<>();
        for (Map<String, Object> row : rawList) {
            dateCountMap.put(String.valueOf(row.get("date")),
                    row.get("count") != null ? ((Number) row.get("count")).longValue() : 0L);
        }

        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate date = from.plusDays(i);
            String key = date.toString();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", key);
            item.put("count", dateCountMap.getOrDefault(key, 0L));
            trend.add(item);
        }

        return Result.success(trend);
    }

    // ======================== 内部方法 ========================

    /**
     * 构建通用查询条件。
     */
    private LambdaQueryWrapper<AlarmRecord> buildQueryWrapper(AlarmQueryRequest request) {
        LambdaQueryWrapper<AlarmRecord> wrapper = new LambdaQueryWrapper<>();

        if (request.getDeviceId() != null && !request.getDeviceId().isBlank()) {
            wrapper.like(AlarmRecord::getDeviceId, request.getDeviceId());
        }
        if (request.getType() != null && !request.getType().isBlank()) {
            wrapper.eq(AlarmRecord::getType, request.getType());
        }
        if (request.getLevel() != null && !request.getLevel().isBlank()) {
            wrapper.eq(AlarmRecord::getLevel, request.getLevel());
        }
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            wrapper.eq(AlarmRecord::getStatus, request.getStatus());
        }
        if (request.getHandler() != null && !request.getHandler().isBlank()) {
            wrapper.like(AlarmRecord::getHandler, request.getHandler());
        }
        if (request.getStartAtFrom() != null) {
            wrapper.ge(AlarmRecord::getStartAt, request.getStartAtFrom());
        }
        if (request.getStartAtTo() != null) {
            wrapper.le(AlarmRecord::getStartAt, request.getStartAtTo());
        }

        wrapper.orderByDesc(AlarmRecord::getStartAt);
        return wrapper;
    }

    /**
     * 将 selectMaps 结果转为 { key: count } 格式。
     */
    private Map<String, Long> buildCountMapV2(List<Map<String, Object>> rawList, String keyField) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (Map<String, Object> row : rawList) {
            Object key = row.get(keyField);
            Object count = row.get("count");
            if (key != null) {
                map.put(String.valueOf(key), count != null ? ((Number) count).longValue() : 0L);
            }
        }
        return map;
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
