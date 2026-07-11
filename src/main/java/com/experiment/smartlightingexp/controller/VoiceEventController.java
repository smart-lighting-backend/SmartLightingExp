package com.experiment.smartlightingexp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.experiment.smartlightingexp.common.RequirePermission;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.entity.VoiceEvent;
import com.experiment.smartlightingexp.service.VoiceEventService;
import com.experiment.smartlightingexp.tdengine.VoiceEventDao;
import com.experiment.smartlightingexp.util.EventTextNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/voice-events")
@RequiredArgsConstructor
public class VoiceEventController {

    private final VoiceEventService voiceEventService;
    private final VoiceEventDao voiceEventDao;

    @RequirePermission("events:read")
    @GetMapping("/page")
    public Result<IPage<VoiceEvent>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String source) {
        List<VoiceEvent> records = voiceEventDao.queryPage(deviceId, type, source, page, size);
        long total = voiceEventDao.countPage(deviceId, type, source);
        records.forEach(EventTextNormalizer::normalizeVoiceEvent);
        Page<VoiceEvent> p = new Page<>(page, size);
        p.setRecords(records);
        p.setTotal(total);
        return Result.success(p);
    }

    @RequirePermission("events:read")
    @GetMapping("/device/{deviceId}")
    public Result<IPage<VoiceEvent>> byDevice(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        List<VoiceEvent> records = voiceEventDao.queryPage(deviceId, null, null, page, size);
        long total = voiceEventDao.countPage(deviceId, null, null);
        records.forEach(EventTextNormalizer::normalizeVoiceEvent);
        Page<VoiceEvent> p = new Page<>(page, size);
        p.setRecords(records);
        p.setTotal(total);
        return Result.success(p);
    }
}
