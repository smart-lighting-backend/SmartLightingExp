# MQTT 模拟数据链路排查与修复记录

> 日期：2026-07-09 | 场景：后端模拟数据无法写入 TDengine，全链路排查并修复

---

## 问题总览

| # | 问题 | 严重程度 | 类型 |
|---|------|----------|------|
| 1 | EMQX 桥接规则 `payload.deviceId` 始终为 null → 数据写入 `t_null` | 致命 | 服务器配置 |
| 2 | Vision/Voice 桥接均用 `v_` 前缀 → TDengine 子表名冲突 | 高 | 服务器配置 |
| 3 | `MqttCallbackExtended.connectComplete()` 同步订阅导致 Paho 死锁 | 致命 | 代码 |
| 4 | 设备 ID 格式不一致（MySQL `SL-001` vs TDengine `SL_001`） | 中 | 数据规范 |
| 5 | MySQL telemetry 降级逻辑掩盖真实故障 | 中 | 代码 |
| 6 | 硬编码 `"streetlight"` 主题前缀 | 中 | 代码 |
| 7 | MQTT 连接超时过长（60s）+ TDengine 池初始化无限等待 | 低 | 配置 |

---

## 问题 1：EMQX 桥接 `payload.deviceId` 始终为 null

**现象**：TDengine 所有遥测数据写入唯一子表 `t_null`，而非 `t_SL_001`、`t_SL_002` 等。

**排查过程**：

1. 通过 TDengine REST API 查询发现 264 条记录，`tbname` 全部是 `t_null`，`device_id` TAG 全是 `SL_001`
2. 手动发送 MQTT 测试消息 `deviceId: "SL-999"`，TAG 依然显示 `SL_001`，确认 `device_id` 不是来自 payload
3. 验证其他字段（`illuminance`、`temperature`、`trafficFlow` 等）全部正确提取，唯独 `payload.deviceId` 不工作

**根因**：EMQX 6.2.1 规则 SQL 中 `payload.deviceId` 无法被提取（始终为 null），原因不明，疑似 EMQX 内部 JSON 解析 bug。`trafficFlow` 等其他 camelCase 字段正常，排除大小写问题。

**解决**：改为从 MQTT topic 中截取设备 ID：

```sql
-- 旧（不工作）
payload.deviceId as dev_id

-- 新（正常工作）
nth(2, split(topic, '/')) as dev_id
```

topic 格式为 `streetlight/{deviceId}/telemetry`，`nth(2, split(...))` 取第 2 段即可获得 deviceId。

**影响范围**：telemetry_bridge、vision_bridge、voice_bridge 三条规则全部修改。

**教训**：EMQX 规则引擎中不要完全依赖 `payload.xxx` 提取关键字段。MQTT topic 中已经包含了设备 ID，从 topic 提取更可靠。

---

## 问题 2：Vision/Voice 桥接子表名冲突

**现象**：vision 事件正常写入，voice 事件全部失败（`actions.failed.unknown`）。

**根因**：两条桥接动作 SQL 都用 `v_${dev_id}` 作为子表名前缀：

```sql
-- vision_action
INSERT INTO v_${dev_id} USING vision_event TAGS (...) VALUES (...)

-- voice_action（冲突！）
INSERT INTO v_${dev_id} USING voice_event TAGS (...) VALUES (...)
```

当 `dev_id = SL_001` 时，两者都尝试创建子表 `v_sl_001`。vision 先创建成功，voice 再创建时 TDengine 拒绝（子表已属于其他超级表）。

**解决**：使用不同前缀区分：

| 超级表 | 前缀 | 示例 |
|--------|------|------|
| `telemetry` | `t_` | `t_sl_001` |
| `vision_event` | `vis_` | `vis_sl_001` |
| `voice_event` | `voi_` | `voi_sl_001` |

**教训**：TDengine 子表名全局唯一，不同超级表的子表不能重名。命名规范在初期就应定好。

---

## 问题 3：`MqttCallbackExtended.connectComplete()` 导致 Paho 死锁（本次最关键）

**现象**：
- 后端启动后 MQTT 连接成功，但首次 `mqttClient.publish()` 调用阻塞 5 秒后抛异常
- 每条消息都超时，模拟数据完全发不出去
- `mqttClient.isConnected()` 返回 true，但 publish 就是卡死

**排查过程**：

1. 加诊断日志发现 `[1/19] 发布遥测: SL_001` 打印后永远看不到 `遥测完成`
2. 给 publish 加 5 秒超时包装后发现消息实际发送成功（MqttSubscriber 收到了），但 `publish()` 调用本身卡了 5 秒
3. 回滚所有改动，逐一排除，最终定位到 `MqttCallbackExtended.connectComplete()` 的改动

**根因**：

```java
// 错误写法 — 在 Paho 内部线程里做同步 MQTT 操作
mqttClient.setCallback(new MqttCallbackExtended() {
    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        subscribeTopics();  // 内部调用了 mqttClient.subscribe()
    }
});
```

Paho 的 `connectComplete()` 回调在 **Paho 内部消息处理线程** 中被调用。`mqttClient.subscribe()` 是同步操作，需要同一个内部线程处理 SUBACK 响应。但该线程正阻塞在 `connectComplete()` 中等待返回 → **死锁**。

死锁后，Paho 内部线程无法处理任何 MQTT 协议通信，所有 `publish()` 调用都被阻塞在内部锁上直到超时。

**解决**：

```java
// 正确写法 — 在新线程中订阅，立即释放 Paho 内部线程
@Override
public void connectComplete(boolean reconnect, String serverURI) {
    log.info("MQTT connected (reconnect={}), server={}", reconnect, serverURI);
    new Thread(() -> subscribeTopics(), "mqtt-resubscribe").start();
}
```

**教训**：**永远不要在 MQTT 回调方法中执行同步 MQTT 操作**（subscribe、publish、disconnect 等）。回调运行在客户端内部线程上，同步操作会造成死锁。需要执行时，提交到独立线程。

---

## 问题 4：设备 ID 格式统一

**现象**：MySQL 设备 ID 用连字符（`SL-001`），TDengine 不支持表名中有 `-`，之前用 `replace(dev_id, '-', '_')` 转换，导致双方不一致。

**解决**：统一改为下划线格式 `SL_001`，MySQL、TDengine、MQTT、前端 Mock 全部一致。

**改动范围**：

| 位置 | 文件 | 数量 |
|------|------|------|
| MySQL 种子数据 | `02_seed_data.sql`, `06_seed_audit_log.sql` | ~30 处 |
| Vue 前端 Mock | `telemetry.js`, `devices.js`, `control.js`, `warnings.js`, `log.js`, `excelTemplate.js`, `DeviceDetail.vue`, `mockStore.js` | ~40 处 |
| Android Mock | `telemetry.js`, `devices.js`, `control.js`, `warnings.js`, `log.js`, `excelTemplate.js`, `DeviceDetail.vue`, `Devices.vue`, `mockStore.js`, `移动端接口文档.md` | ~30 处 |
| EMQX 桥接 | 3 条规则 + 3 个动作 | 去掉 `replace()` |

---

## 问题 5：MySQL telemetry 降级逻辑掩盖故障

**现象**：多个 Controller/Task 中 try-catch 了 `DataAccessException`，TDengine 查询失败时静默降级到 MySQL 查询。但 MySQL telemetry 表从未有数据写入（数据全靠 EMQX 桥接到 TDengine），导致降级返回空结果，前端再降级到 Mock 数据。

三层降级链：`TDengine 故障 → 静默查空 MySQL → 前端降级 Mock`，完全掩盖了真实问题。

**解决**：移除所有 `try { TDengine } catch (DataAccessException) { MySQL }` 降级逻辑（8 处），TDengine 故障直接抛异常。

涉及文件：`TelemetryController.java`、`VisionEventController.java`、`VoiceEventController.java`、`HealthScoreTask.java`、`EdgeNodeSimulator.java`、`DeviceController.java`

---

## 问题 6：硬编码 MQTT 主题前缀

**现象**：`MqttSubscriber.java`、`DecisionEngine.java`、`ControlController.java` 中 6 处写死 `"streetlight"` 字符串，而 `application.yaml` 中有 `mqtt.topic-prefix` 配置项。改了配置就会导致订阅/下发静默失效。

**解决**：注入 `MqttProperties`，统一使用 `mqttProperties.getTopicPrefix()`。

---

## 问题 7：MQTT 连接超时 + TDengine 池初始化超时

**现象**：
- MQTT 连接超时设为 60 秒，启动时若网络慢会卡很久
- TDengine HikariCP 连接池 `initializationFailTimeout=-1`（无限等待），若 TDengine 不可达则应用永远起不来

**解决**：

| 配置 | 旧值 | 新值 |
|------|------|------|
| `MqttConfig.connectionTimeout` | 60s | 10s |
| `MqttConfig.keepAliveInterval` | 60s | 15s |
| `TdengineConfig.initializationFailTimeout` | -1（无限） | 0（不阻塞启动） |

---

## 最终验证

```
Mock心跳: 19/19 台设备已发布 (0台跳过)
Mock遥测完成: 19/19 台设备 (共4批)
遥测处理 [SL_001]: lux=1010, temp=35.34°C
自动控制 [SL_001]: DIMMING(45) (策略=光照低自动开灯)
...
遥测处理 [SL_019]: lux=410, temp=36.41°C
自动控制 [SL_019]: DIMMING(45) (策略=光照低自动开灯)
```

全链路：MockDataGenerator → MQTT → EMQX 桥接 → TDengine ✓  
         MockDataGenerator → MQTT → MqttSubscriber → DecisionEngine ✓

---

## 经验教训

1. **EMQX 规则引擎不可靠依赖 `payload.xxx` 提取关键字段**，优先从 MQTT topic 获取
2. **Paho MQTT 回调中绝对不能做同步 MQTT 操作**，会死锁内部线程
3. **降级逻辑要有意义**，降级到空数据源等于没降级，反而掩盖问题
4. **不要硬编码可配置值**，配置改了会出现静默 bug
5. **TDengine 子表名全局唯一**，不同超级表需要不同前缀避免冲突
6. **超时设置要合理**：太长阻塞启动，太短容易误判断连
