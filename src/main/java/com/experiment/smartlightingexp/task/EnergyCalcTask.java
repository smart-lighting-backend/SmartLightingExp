package com.experiment.smartlightingexp.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.experiment.smartlightingexp.entity.ControlCommand;
import com.experiment.smartlightingexp.entity.Device;
import com.experiment.smartlightingexp.entity.EnergyRecord;
import com.experiment.smartlightingexp.mapper.ControlCommandMapper;
import com.experiment.smartlightingexp.mapper.DeviceMapper;
import com.experiment.smartlightingexp.mapper.EnergyRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class EnergyCalcTask {

    private final DeviceMapper deviceMapper;
    private final ControlCommandMapper controlCommandMapper;
    private final EnergyRecordMapper energyRecordMapper;

    /** 默认额定功率（W） */
    private static final BigDecimal DEFAULT_RATED_POWER = BigDecimal.valueOf(150);
    /** 默认亮灯时长（分钟 = 12 小时） */
    private static final int DEFAULT_ON_DURATION_MIN = 720;
    /** 国家电网排放因子 kg CO₂/kWh */
    private static final BigDecimal CARBON_FACTOR = BigDecimal.valueOf(0.785);

    /**
     * 每日 23:55 执行能耗统计。
     * 遍历所有已启用设备，读取当日控制指令，计算时间加权平均亮度，
     * 估算用电量、节能率和碳减排量，写入 energy_record 表（upsert）。
     */
    @Scheduled(cron = "0 55 23 * * ?")
    public void calcDailyEnergy() {
        LocalDate today = LocalDate.now();
        log.info("===== EnergyCalcTask start for {} =====", today);

        List<Device> devices = deviceMapper.selectList(
                Wrappers.<Device>lambdaQuery()
                        .eq(Device::getEnabled, true)
                        .eq(Device::getDeleted, false));

        if (devices.isEmpty()) {
            log.warn("No enabled devices found, skip");
            return;
        }

        int successCount = 0;
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime dayEnd = today.atTime(LocalTime.MAX);

        for (Device device : devices) {
            try {
                // 1. 查询当日控制指令
                List<ControlCommand> commands = controlCommandMapper
                        .selectByDeviceAndTimeRange(device.getDeviceId(), dayStart, dayEnd);

                // 2. 时间加权平均亮度
                double avgBrightness = calcAvgBrightness(commands, dayEnd);

                // 3. 亮灯时长（分钟）
                int onDurationMin = calcOnDuration(commands, dayEnd);

                // 4. 额定功率（兜底 150W）
                BigDecimal ratedPower = device.getRatedPower() != null
                        ? device.getRatedPower() : DEFAULT_RATED_POWER;
                BigDecimal ratedKw = ratedPower.divide(BigDecimal.valueOf(1000), 10, RoundingMode.HALF_UP);

                // 5. 亮灯小时数
                BigDecimal onDurationH = BigDecimal.valueOf(onDurationMin)
                        .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);

                // 6. 估算用电量
                BigDecimal brightnessFactor = BigDecimal.valueOf(avgBrightness)
                        .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
                BigDecimal estimatedKwh = ratedKw
                        .multiply(onDurationH)
                        .multiply(brightnessFactor)
                        .setScale(4, RoundingMode.HALF_UP);

                // 7. 基准用电（满亮度）
                BigDecimal baselineKwh = ratedKw
                        .multiply(onDurationH)
                        .setScale(4, RoundingMode.HALF_UP);

                // 8. 节能率
                BigDecimal savingRate = BigDecimal.valueOf(100 - avgBrightness)
                        .setScale(1, RoundingMode.HALF_UP);
                if (savingRate.compareTo(BigDecimal.ZERO) < 0) {
                    savingRate = BigDecimal.ZERO;
                }

                // 9. 碳减排量
                BigDecimal savedKwh = baselineKwh.subtract(estimatedKwh);
                BigDecimal carbonReduction = savedKwh.multiply(CARBON_FACTOR)
                        .setScale(4, RoundingMode.HALF_UP);

                // 10. Upsert：先查后写（防重复）
                EnergyRecord existing = energyRecordMapper.selectOne(
                        Wrappers.<EnergyRecord>lambdaQuery()
                                .eq(EnergyRecord::getDeviceId, device.getDeviceId())
                                .eq(EnergyRecord::getRecordDate, today));

                if (existing != null) {
                    existing.setOnDurationMin(onDurationMin);
                    existing.setAvgBrightness(BigDecimal.valueOf(avgBrightness)
                            .setScale(2, RoundingMode.HALF_UP));
                    existing.setEstimatedKwh(estimatedKwh);
                    existing.setSavingRate(savingRate);
                    existing.setCarbonReduction(carbonReduction);
                    energyRecordMapper.updateById(existing);
                } else {
                    EnergyRecord record = new EnergyRecord();
                    record.setDeviceId(device.getDeviceId());
                    record.setRecordDate(today);
                    record.setOnDurationMin(onDurationMin);
                    record.setAvgBrightness(BigDecimal.valueOf(avgBrightness)
                            .setScale(2, RoundingMode.HALF_UP));
                    record.setEstimatedKwh(estimatedKwh);
                    record.setSavingRate(savingRate);
                    record.setCarbonReduction(carbonReduction);
                    energyRecordMapper.insert(record);
                }

                successCount++;
                log.debug("[{}] brightness={}%, on={}min, kWh={}, save={}%",
                        device.getDeviceId(), String.format("%.1f", avgBrightness),
                        onDurationMin, estimatedKwh, savingRate);
            } catch (Exception e) {
                log.error("[{}] EnergyCalcTask error: {}", device.getDeviceId(), e.getMessage());
            }
        }

        log.info("===== EnergyCalcTask done: {}/{} =====", successCount, devices.size());
    }

    /**
     * 时间加权平均亮度。
     * 按 issued_at 排序，每段亮度持续到下一指令或当日结束。
     * 无调光指令时默认 100%（满亮度 = 节能率 0%）。
     */
    private double calcAvgBrightness(List<ControlCommand> commands, LocalDateTime dayEnd) {
        List<ControlCommand> dimmingCmds = commands.stream()
                .filter(c -> c.getBrightness() != null && c.getIssuedAt() != null)
                .sorted(Comparator.comparing(ControlCommand::getIssuedAt))
                .collect(Collectors.toList());

        if (dimmingCmds.isEmpty()) {
            return 100.0;
        }

        double totalWeight = 0;
        long totalMinutes = 0;

        for (int i = 0; i < dimmingCmds.size(); i++) {
            ControlCommand cmd = dimmingCmds.get(i);
            LocalDateTime from = cmd.getIssuedAt();
            LocalDateTime to = (i + 1 < dimmingCmds.size())
                    ? dimmingCmds.get(i + 1).getIssuedAt()
                    : dayEnd;

            long minutes = Duration.between(from, to).toMinutes();
            if (minutes <= 0) continue;

            totalWeight += (double) cmd.getBrightness() * minutes;
            totalMinutes += minutes;
        }

        if (totalMinutes == 0) return 100.0;
        return totalWeight / totalMinutes;
    }

    /**
     * 亮灯时长（分钟）。有调光指令时以第一条指令到当日结束计算，
     * 无指令时默认 12 小时（720 min）。
     */
    private int calcOnDuration(List<ControlCommand> commands, LocalDateTime dayEnd) {
        LocalDateTime firstCmd = commands.stream()
                .filter(c -> c.getIssuedAt() != null)
                .map(ControlCommand::getIssuedAt)
                .min(Comparator.naturalOrder())
                .orElse(null);

        if (firstCmd == null) {
            return DEFAULT_ON_DURATION_MIN;
        }

        long minutes = Duration.between(firstCmd, dayEnd).toMinutes();
        return (int) Math.min(Math.max(minutes, 60), DEFAULT_ON_DURATION_MIN);
    }

    /**
     * 为过去 N 天生成模拟能耗测试数据（跳过已有记录的日期）。
     * 每天为每个已启用设备生成随机但合理的能耗记录。
     */
    public void generateHistoricalData(int days) {
        LocalDate today = LocalDate.now();
        List<Device> devices = deviceMapper.selectList(
                Wrappers.<Device>lambdaQuery()
                        .eq(Device::getEnabled, true)
                        .eq(Device::getDeleted, false));

        if (devices.isEmpty()) {
            log.warn("No enabled devices found, skip historical data generation");
            return;
        }

        Random random = new Random();
        int totalGenerated = 0;

        for (int d = days; d >= 1; d--) {
            LocalDate recordDate = today.minusDays(d);
            for (Device device : devices) {
                try {
                    EnergyRecord existing = energyRecordMapper.selectOne(
                            Wrappers.<EnergyRecord>lambdaQuery()
                                    .eq(EnergyRecord::getDeviceId, device.getDeviceId())
                                    .eq(EnergyRecord::getRecordDate, recordDate));
                    if (existing != null) continue;

                    int onDurationMin = 300 + random.nextInt(421);
                    double avgBrightness = 30 + random.nextDouble() * 70;

                    BigDecimal ratedPower = device.getRatedPower() != null
                            ? device.getRatedPower() : DEFAULT_RATED_POWER;
                    BigDecimal ratedKw = ratedPower.divide(BigDecimal.valueOf(1000), 10, RoundingMode.HALF_UP);
                    BigDecimal onDurationH = BigDecimal.valueOf(onDurationMin)
                            .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
                    BigDecimal brightnessFactor = BigDecimal.valueOf(avgBrightness)
                            .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
                    BigDecimal estimatedKwh = ratedKw.multiply(onDurationH).multiply(brightnessFactor)
                            .setScale(4, RoundingMode.HALF_UP);
                    BigDecimal baselineKwh = ratedKw.multiply(onDurationH).setScale(4, RoundingMode.HALF_UP);
                    BigDecimal savingRate = BigDecimal.valueOf(100 - avgBrightness)
                            .setScale(1, RoundingMode.HALF_UP);
                    if (savingRate.compareTo(BigDecimal.ZERO) < 0) savingRate = BigDecimal.ZERO;
                    BigDecimal savedKwh = baselineKwh.subtract(estimatedKwh);
                    BigDecimal carbonReduction = savedKwh.multiply(CARBON_FACTOR)
                            .setScale(4, RoundingMode.HALF_UP);

                    EnergyRecord record = new EnergyRecord();
                    record.setDeviceId(device.getDeviceId());
                    record.setRecordDate(recordDate);
                    record.setOnDurationMin(onDurationMin);
                    record.setAvgBrightness(BigDecimal.valueOf(avgBrightness).setScale(2, RoundingMode.HALF_UP));
                    record.setEstimatedKwh(estimatedKwh);
                    record.setSavingRate(savingRate);
                    record.setCarbonReduction(carbonReduction);
                    energyRecordMapper.insert(record);
                    totalGenerated++;
                } catch (Exception e) {
                    log.error("[{}] gen historical data error for {}: {}",
                            device.getDeviceId(), recordDate, e.getMessage());
                }
            }
        }
        log.info("===== Historical data generation done: {} records =====", totalGenerated);
    }
}
