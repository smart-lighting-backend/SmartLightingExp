package com.experiment.smartlightingexp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.dto.DeviceCreateRequest;
import com.experiment.smartlightingexp.dto.DevicePageRequest;
import com.experiment.smartlightingexp.dto.DeviceUpdateRequest;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.service.DeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @PostMapping
    public Result<Device> createDevice(@Valid @RequestBody DeviceCreateRequest request) {
        return Result.success(deviceService.createDevice(request));
    }

    @PutMapping("/{deviceId}")
    public Result<Device> updateDevice(@PathVariable String deviceId,
                                       @Valid @RequestBody DeviceUpdateRequest request) {
        return Result.success(deviceService.updateDevice(deviceId, request));
    }

    @DeleteMapping("/{deviceId}")
    public Result<Void> deleteDevice(@PathVariable String deviceId) {
        deviceService.deleteDevice(deviceId);
        return Result.success();
    }

    @GetMapping("/{deviceId}")
    public Result<Device> getDeviceDetail(@PathVariable String deviceId) {
        return Result.success(deviceService.getDeviceDetail(deviceId));
    }

    @GetMapping("/page")
    public Result<Page<Device>> pageDevices(@ModelAttribute DevicePageRequest request) {
        return Result.success(deviceService.pageDevices(request));
    }
}
