package com.experiment.smartlightingexp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.entity.AuditLog;
import com.experiment.smartlightingexp.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping("/system")
    public Result<Page<AuditLog>> getSystemLogs(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo) {

        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(AuditLog::getOperatedAt);

        if (operator != null && !operator.isEmpty())
            wrapper.eq(AuditLog::getOperator, operator);
        if (action != null && !action.isEmpty())
            wrapper.eq(AuditLog::getAction, action);
        if (targetType != null && !targetType.isEmpty())
            wrapper.eq(AuditLog::getTargetType, targetType);
        if (result != null && !result.isEmpty())
            wrapper.eq(AuditLog::getResult, result);
        if (dateFrom != null)
            wrapper.ge(AuditLog::getOperatedAt, dateFrom);
        if (dateTo != null)
            wrapper.le(AuditLog::getOperatedAt, dateTo);

        Page<AuditLog> p = auditLogService.page(new Page<>(page, size), wrapper);
        return Result.success(p);
    }
}
