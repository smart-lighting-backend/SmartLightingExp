package com.experiment.smartlightingexp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.experiment.smartlightingexp.common.RequirePermission;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.entity.VisionEvent;
import com.experiment.smartlightingexp.service.VisionEventService;
import com.experiment.smartlightingexp.util.EventTextNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/vision-events")
@RequiredArgsConstructor
public class VisionEventController {

    private final VisionEventService visionEventService;

    @RequirePermission("events:read")
    @GetMapping("/page")
    public Result<IPage<VisionEvent>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String eventType) {
        LambdaQueryWrapper<VisionEvent> wrapper = new LambdaQueryWrapper<>();
        if (deviceId != null && !deviceId.isBlank()) {
            wrapper.eq(VisionEvent::getDeviceId, deviceId);
        }
        if (eventType != null && !eventType.isBlank()) {
            wrapper.in(VisionEvent::getEventType, EventTextNormalizer.queryValues(eventType));
        }
        wrapper.orderByDesc(VisionEvent::getOccurredAt);
        IPage<VisionEvent> result = visionEventService.page(new Page<>(page, size), wrapper);
        result.getRecords().forEach(EventTextNormalizer::normalizeVisionEvent);
        return Result.success(result);
    }

    @RequirePermission("events:read")
    @GetMapping("/device/{deviceId}")
    public Result<IPage<VisionEvent>> byDevice(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        LambdaQueryWrapper<VisionEvent> wrapper = new LambdaQueryWrapper<VisionEvent>()
                .eq(VisionEvent::getDeviceId, deviceId)
                .orderByDesc(VisionEvent::getOccurredAt);
        IPage<VisionEvent> result = visionEventService.page(new Page<>(page, size), wrapper);
        result.getRecords().forEach(EventTextNormalizer::normalizeVisionEvent);
        return Result.success(result);
    }
}
