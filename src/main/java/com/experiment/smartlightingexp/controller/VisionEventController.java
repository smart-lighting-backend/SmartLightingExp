package com.experiment.smartlightingexp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.experiment.smartlightingexp.common.RequirePermission;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.entity.VisionEvent;
import com.experiment.smartlightingexp.service.VisionEventService;
import com.experiment.smartlightingexp.tdengine.VisionEventDao;
import com.experiment.smartlightingexp.util.EventTextNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/vision-events")
@RequiredArgsConstructor
public class VisionEventController {

    private final VisionEventService visionEventService;
    private final VisionEventDao visionEventDao;

    @RequirePermission("events:read")
    @GetMapping("/page")
    public Result<IPage<VisionEvent>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String eventType) {
        List<VisionEvent> records = visionEventDao.queryPage(deviceId, eventType, page, size);
        long total = visionEventDao.countPage(deviceId, eventType);
        records.forEach(EventTextNormalizer::normalizeVisionEvent);
        Page<VisionEvent> p = new Page<>(page, size);
        p.setRecords(records);
        p.setTotal(total);
        return Result.success(p);
    }

    @RequirePermission("events:read")
    @GetMapping("/device/{deviceId}")
    public Result<IPage<VisionEvent>> byDevice(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        List<VisionEvent> records = visionEventDao.queryPage(deviceId, null, page, size);
        long total = visionEventDao.countPage(deviceId, null);
        records.forEach(EventTextNormalizer::normalizeVisionEvent);
        Page<VisionEvent> p = new Page<>(page, size);
        p.setRecords(records);
        p.setTotal(total);
        return Result.success(p);
    }
}
