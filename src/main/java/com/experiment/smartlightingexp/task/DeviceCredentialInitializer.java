package com.experiment.smartlightingexp.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.entity.DeviceCredential;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.service.DeviceCredentialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 存量设备凭证初始化器。
 * 应用启动后，自动为所有已启用、未删除、尚无凭证的设备生成 MQTT 鉴权凭证。
 * 出厂编号默认使用 "AUTO-{deviceId}" 格式，后续可在前端编辑修改。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceCredentialInitializer implements CommandLineRunner {

    private final DeviceMapper deviceMapper;
    private final DeviceCredentialService credentialService;

    @Override
    public void run(String... args) {
        try {
            List<Device> devices = deviceMapper.selectList(
                    new LambdaQueryWrapper<Device>()
                            .eq(Device::getDeleted, false)
                            .eq(Device::getEnabled, true));
            if (devices.isEmpty()) {
                log.info("[凭证初始化] 无已启用设备，跳过");
                return;
            }

            int created = 0;
            int skipped = 0;
            for (Device device : devices) {
                try {
                    DeviceCredential existing = credentialService.getByDeviceId(device.getDeviceId());
                    if (existing == null) {
                        String factorySerial = "AUTO-" + device.getDeviceId();
                        credentialService.createCredential(device.getDeviceId(), factorySerial);
                        created++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    log.warn("[凭证初始化] 设备 {} 凭证生成失败: {}", device.getDeviceId(), e.getMessage());
                }
            }

            log.info("[凭证初始化] 完成: {} 台设备已有凭证, {} 台新生成, {} 台总计",
                    skipped, created, devices.size());
        } catch (Exception e) {
            log.error("[凭证初始化] 执行失败: {}", e.getMessage());
        }
    }
}
