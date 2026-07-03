# 能耗统计实现方案 — C（DecisionEngine 联动计算）

> 基于 2026-07-03 方案评估，选择"策略联动 + 独立定时任务"路线
> 接入真实硬件后计算逻辑无需改动

---

## 一、整体数据流

```
真实硬件/Mock → MQTT → MqttSubscriber → DecisionEngine
                                            ↓
                                      下发 DIMMING(n)
                                            ↓
                                  control_command 表
                                  (brightness, issued_at)
                                            ↓
                               EnergyCalcTask (每日 23:55)
                                            ↓
                               ┌─ on_duration_min    (亮灯总分钟)
                               ├─ avg_brightness      (时间加权平均亮度)
                               ├─ estimated_kwh       (实际用电量)
                               ├─ saving_rate         (1 - avg/100) × 100%
                               └─ carbon_reduction    (kWh × 0.785)
                                            ↓
                                  energy_record 表
                                            ↓
                              Dashboard 显示节能率
```

## 二、计算逻辑

### 2.1 时间加权平均亮度

读取设备当日 `control_command` 记录，按时间排序，计算加权平均：

```
时间段      → 亮度     → 时长(h) → 加权
18:00-22:00 → 100%     → 4       → 400
22:00-23:00 → 50%      → 1       → 50
23:00-06:00 → 30%      → 7       → 210
          合计          12        660

avg_brightness = 660 / 12 = 55%
```

### 2.2 节能率

```
saving_rate = (100 - avg_brightness) / 100 × 100%

示例：avg_brightness = 55% → saving_rate = 45%
```

### 2.3 估算用电量

```
estimated_kwh = rated_power_W / 1000 × on_duration_h × (avg_brightness / 100)

示例：150W × 12h × 0.55 = 0.99 kWh（单灯当日）
```

### 2.4 碳减排估算

```
saved_kwh = (rated_power_W / 1000 × on_duration_h) - estimated_kwh
carbon_reduction = saved_kwh × 0.785    -- 国家电网排放因子 kg CO₂/kWh
```

## 三、需要变更的文件

### 3.1 数据库变更

| 变更 | SQL | 说明 |
|------|-----|------|
| device 表加 rated_power | `ALTER TABLE device ADD COLUMN rated_power DECIMAL(10,2) DEFAULT 150.00 COMMENT '额定功率(W)'` | 用于 kWh 计算 |

### 3.2 后端新增

| 文件 | 说明 |
|------|------|
| `task/EnergyCalcTask.java` | 定时任务，每日 23:55 执行 |

### 3.3 后端已就绪（无需动）

| 组件 | 文件 |
|------|------|
| Entity | `entity/EnergyRecord.java` |
| Mapper | `mapper/EnergyRecordMapper.java` |
| Service (空壳) | `service/EnergyRecordService.java` + `impl/EnergyRecordServiceImpl.java` |

## 四、EnergyCalcTask 伪代码

```
// 1. 查询所有已启用设备
List<Device> devices = deviceMapper.selectEnabled();

for (Device device : devices) {
    // 2. 查询当日控制指令（按时间排序）
    List<ControlCommand> commands = controlCommandMapper
        .selectTodayByDevice(device.getDeviceId());

    // 3. 计算时间加权平均亮度
    double avgBrightness = calculateTimeWeightedBrightness(commands);

    // 4. 计算亮灯时长（从第一条 ON 到最后一条 OFF / 标准夜间时段）
    int onDurationMin = calculateOnDuration(commands);

    // 5. 计算各项指标
    BigDecimal ratedKw     = device.getRatedPower() / 1000;
    BigDecimal baselineKwh = ratedKw * onDurationMin / 60;
    BigDecimal actualKwh   = baselineKwh * avgBrightness / 100;
    BigDecimal savingRate  = 100 - avgBrightness;
    BigDecimal carbonReduc = (baselineKwh - actualKwh) * 0.785;

    // 6. 写入 energy_record
    energyRecordMapper.insert(new EnergyRecord(
        device.getDeviceId(), today, onDurationMin, avgBrightness, actualKwh, savingRate, carbonReduc
    ));
}
```

## 五、接入真实硬件后的适配

| 环节 | Mock 态 | 真实硬件态 | 代码改动 |
|------|---------|-----------|---------|
| 数据来源 | MockDataGenerator 随机遥测 | 传感器上报真实遥测 | 无 |
| 策略执行 | DecisionEngine 按随机数据匹配 | DecisionEngine 按真实数据匹配 | 无 |
| 指令记录 | 控制指令写入 control_command | 控制指令写入 control_command | 无 |
| 能耗计算 | EnergyCalcTask 读取 command 记录 | EnergyCalcTask 读取 command 记录 | **无** |

真实硬件场景下，`control_command` 的 `ack_at` 字段可获知设备实际执行时间，使 `on_duration_min` 计算更精确。
