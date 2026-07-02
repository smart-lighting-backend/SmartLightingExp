package com.experiment.smartlightingexp.controller;

import com.experiment.smartlightingexp.common.Result;
import com.experiment.smartlightingexp.dto.ControlRequest;
import com.experiment.smartlightingexp.entity.ControlCommand;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.mapper.ControlCommandMapper;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.mqtt.MqttPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class ControlController {

    private final DeviceMapper deviceMapper;
    private final ControlCommandMapper controlCommandMapper;
    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;

    private static final List<String> VALID_ACTIONS = List.of("ON", "OFF", "DIMMING");

    /**
     * 手动控制设备（开/关/调光）。
     * 记录手动操作时间 → AI 策略引擎在 30 分钟内跳过此设备。
     */
    @PostMapping("/{deviceId}/control")
    public Result<Void> control(@PathVariable String deviceId,
                                @Valid @RequestBody ControlRequest request) {
        Device device = deviceMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Device>()
                        .eq(Device::getDeviceId, deviceId));
        if (device == null) {
            return Result.error("设备不存在");
        }
        if (!VALID_ACTIONS.contains(request.getAction())) {
            return Result.error("无效指令类型，支持: ON/OFF/DIMMING");
        }
        if ("DIMMING".equals(request.getAction()) && request.getBrightness() == null) {
            return Result.error("DIMMING 指令需提供 brightness 参数");
        }

        // 1. 组装指令标识（如 ON / OFF / DIMMING(70)）
        String cmdStr = "DIMMING".equals(request.getAction())
                ? "DIMMING(" + request.getBrightness() + ")"
                : request.getAction();

        // 2. 发布 MQTT 控制指令
        Map<String, Object> cmdPayload = new HashMap<>();
        cmdPayload.put("action", cmdStr);
        cmdPayload.put("issuedAt", LocalDateTime.now().toString());
        cmdPayload.put("source", "MANUAL");
        try {
            mqttPublisher.publish(
                    "streetlight/" + deviceId + "/command",
                    objectMapper.writeValueAsString(cmdPayload),
                    1);
        } catch (Exception e) {
            log.error("[{}] MQTT publish failed: {}", deviceId, e.getMessage());
            return Result.error("MQTT 下发失败");
        }

        // 3. 记录 control_command 流水
        ControlCommand cmd = new ControlCommand();
        cmd.setDeviceId(deviceId);
        cmd.setAction(cmdStr);
        cmd.setBrightness("DIMMING".equals(request.getAction()) ? request.getBrightness() : null);
        cmd.setSource("MANUAL");
        cmd.setStatus("SENT");
        cmd.setIssuedAt(LocalDateTime.now());
        cmd.setResultDetail("手动控制-" + cmdStr);
        controlCommandMapper.insert(cmd);

        // 4. 更新设备 lastManualAt — AI 锁定 30 分钟
        deviceMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Device>()
                        .eq(Device::getDeviceId, deviceId)
                        .set(Device::getLastManualAt, LocalDateTime.now()));
        log.info("[{}] Manual control → {} (lastManualAt=now)", deviceId, cmdStr);

        return Result.success();
    }
}
