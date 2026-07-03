package com.experiment.smartlightingexp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.dto.DevicePageRequest;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @GetMapping("/page")
    public Result<Page<Device>> pageDevices(@ModelAttribute DevicePageRequest request) {
        return Result.success(deviceService.pageDevices(request));
    }
}
