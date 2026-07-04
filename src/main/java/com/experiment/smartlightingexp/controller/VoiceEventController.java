package com.experiment.smartlightingexp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.entity.VoiceEvent;
import com.experiment.smartlightingexp.service.VoiceEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/voice-events")
@RequiredArgsConstructor
public class VoiceEventController {

    private final VoiceEventService voiceEventService;

    @GetMapping("/page")
    public Result<IPage<VoiceEvent>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String type) {
        LambdaQueryWrapper<VoiceEvent> wrapper = new LambdaQueryWrapper<>();
        if (deviceId != null && !deviceId.isBlank()) {
            wrapper.eq(VoiceEvent::getDeviceId, deviceId);
        }
        if (type != null && !type.isBlank()) {
            wrapper.eq(VoiceEvent::getType, type);
        }
        wrapper.orderByDesc(VoiceEvent::getOccurredAt);
        return Result.success(voiceEventService.page(new Page<>(page, size), wrapper));
    }

    @GetMapping("/device/{deviceId}")
    public Result<IPage<VoiceEvent>> byDevice(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        LambdaQueryWrapper<VoiceEvent> wrapper = new LambdaQueryWrapper<VoiceEvent>()
                .eq(VoiceEvent::getDeviceId, deviceId)
                .orderByDesc(VoiceEvent::getOccurredAt);
        return Result.success(voiceEventService.page(new Page<>(page, size), wrapper));
    }
}
