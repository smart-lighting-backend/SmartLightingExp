package com.experiment.smartlightingexp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.experiment.smartlightingexp.common.RequirePermission;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.dto.AlarmPageRequest;
import com.experiment.smartlightingexp.entity.AlarmRecord;
import com.experiment.smartlightingexp.service.AlarmRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alarms")
@RequiredArgsConstructor
public class AlarmRecordController {

    private final AlarmRecordService alarmRecordService;

    @RequirePermission("alarm:read")
    @GetMapping("/page")
    public Result<Page<AlarmRecord>> pageAlarms(@ModelAttribute AlarmPageRequest request) {
        return Result.success(alarmRecordService.pageAlarms(request));
    }
}
