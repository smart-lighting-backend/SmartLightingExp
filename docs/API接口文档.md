# 智慧照明系统 API 接口文档

> 文档版本：1.0.0
> 对应代码库：SmartLightingExp
> Knife4j 在线文档：启动项目后访问 `/doc.html`

---

## 一、通用说明

### 1.1 基础地址

```
http://localhost:8080
```

### 1.2 统一响应格式

所有接口返回统一格式：

```json
{
  "code": 200,
  "msg": "success",
  "data": null
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | int | 业务状态码：200 成功，400 参数错误，500 服务端错误 |
| `msg` | string | 提示信息 |
| `data` | object | 响应数据，成功时返回具体内容，失败时为 null |

### 1.3 HTTP 状态码与业务 code 对应规则

| HTTP 状态码 | 业务 code | 场景 |
|:----------:|:--------:|------|
| 200 | 200 | 操作成功 |
| 400 | 400 | 参数校验失败 |
| 500 | 500 | 业务异常（如设备不存在） |
| 500 | 500 | 未捕获的服务器内部错误 |

### 1.4 认证方式（开发中）

> 当前接口暂未接入 JWT 认证，后续由拦截器统一校验 `Authorization: Bearer <token>` 头。

---

## 二、设备手动控制

### 2.1 手动控制设备

下发开灯、关灯、调光指令。

**请求**

```
POST /api/devices/{deviceId}/control
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `deviceId` | string | 是 | 设备唯一编号（如 SL-001） |

**请求体（JSON）**

```json
{
  "action": "DIMMING",
  "brightness": 70
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `action` | string | 是 | 指令类型：`ON`（开灯）/ `OFF`（关灯）/ `DIMMING`（调光） |
| `brightness` | int | 仅在 DIMMING 时必填 | 亮度值，范围 0~100 |

**响应示例 — 成功**

```json
{
  "code": 200,
  "msg": "success",
  "data": null
}
```

**响应示例 — 设备不存在**

```json
{
  "code": 500,
  "msg": "设备不存在",
  "data": null
}
```

**响应示例 — 参数错误**

```json
{
  "code": 400,
  "msg": "action: 指令类型不能为空",
  "data": null
}
```

**业务流程说明**

```
用户请求 → 校验设备/参数
    ↓
MQTT 发布 → streetlight/{deviceId}/command
    ↓
control_command 表插入（source=MANUAL, status=SENT）
    ↓
device.last_manual_at = now（AI 锁定 30 分钟，防覆盖）
    ↓
返回成功
```

**涉及数据库表**

| 表 | 操作 | 说明 |
|---|:----:|------|
| `device` | 查询 + 更新 | 校验设备是否存在，更新 `last_manual_at` |
| `control_command` | 插入 | 记录指令流水 |

---

## 三、AI 自动控制（事件驱动，无 API 端点）

### 3.1 触发方式

AI 策略引擎 `DecisionEngine` 不由 HTTP 接口触发，而是在 `MqttSubscriber` 收到遥测数据后自动调用。

### 3.2 触发链路

```
MQTT 遥测到达 → MqttSubscriber.messageArrived()
    ↓ 入库 telemetry + 更新 latest_data
    ↓ 调用 DecisionEngine.evaluate()
```

### 3.3 决策流程

```
DecisionEngine.evaluate(deviceId, telemetry)
    │
    ├─ 1. 设备存在？已启用？
    │     └─ 否 → return
    │
    ├─ 2. 手动锁定检测
    │     ├─ lastManualAt == null → 继续（未锁定）
    │     └─ lastManualAt 在 30 分钟内 → return（锁定中）
    │
    ├─ 3. 查询启用的照明策略（按优先级升序）
    │
    ├─ 4. 遍历策略，解析 conditions JSON，匹配遥测
    │     └─ 匹配 → 取优先级最高的策略（break）
    │
    ├─ 5. 结果处理
    │     ├─ 匹配 → MQTT 下发指令 + 记录 control_command + 记录 decision_log
    │     └─ 不匹配 → 记录 decision_log（NO_MATCH）
    │
    └─ 完成
```

### 3.4 策略条件语法

`lighting_policy` 表的 `conditions` 字段为 JSON 格式，支持以下条件键：

| 条件键 | 类型 | 说明 | 示例 |
|--------|------|------|------|
| `lux_lt` | number | 光照强度低于（lux） | `{"lux_lt": 50}` |
| `lux_gt` | number | 光照强度高于（lux） | `{"lux_gt": 200}` |
| `temp_lt` | number | 温度低于（℃） | `{"temp_lt": 0}` |
| `temp_gt` | number | 温度高于（℃） | `{"temp_gt": 40}` |
| `humidity_lt` | number | 湿度低于（%） | `{"humidity_lt": 30}` |
| `humidity_gt` | number | 湿度高于（%） | `{"humidity_gt": 80}` |
| `pir` | number | 人体红外（0=无人，1=有人） | `{"pir": 1}` |
| `traffic_gt` | number | 车流量大于 | `{"traffic_gt": 10}` |
| `traffic_lt` | number | 车流量小于 | `{"traffic_lt": 3}` |
| `time_range` | string | 时间段（支持跨天） | `{"time_range": "23:00-05:59"}` |

多个条件为 **AND** 关系：`{"lux_lt": 50, "pir": 1}` 表示光照低于 50 **且** 有人经过。

### 3.5 涉及数据库表

| 表 | 操作 | 说明 |
|---|:----:|------|
| `device` | 查询 | 检查设备状态和手动锁定 |
| `lighting_policy` | 查询 | 获取启用的策略规则 |
| `telemetry` | 查询 | 传入参数，不做数据库查询 |
| `control_command` | 插入 | 记录自动下发的控制指令（source=AUTO） |
| `decision_log` | 插入 | 记录决策日志（输入快照、命中策略、执行结果） |

---

## 四、MQTT 主题

### 4.1 上行（设备 → 云）

| Topic | 方向 | 说明 | 状态 |
|-------|:----:|------|:----:|
| `streetlight/{deviceId}/telemetry` | 设备→云 | 遥测数据上报（光照、温湿度、PIR 等） | ✅ **已订阅** |
| `streetlight/{deviceId}/heartbeat` | 设备→云 | 设备心跳 | ❌ 未订阅 |
| `streetlight/{deviceId}/vision/event` | 设备→云 | AI 视觉识别事件 | ❌ 未订阅 |
| `streetlight/{deviceId}/voice/event` | 设备→云 | 语音交互事件 | ❌ 未订阅 |
| `streetlight/{deviceId}/command/ack` | 设备→云 | 控制指令执行反馈 | ❌ 未订阅 |

### 4.2 下行（云 → 设备）

| Topic | 方向 | 说明 | 状态 |
|-------|:----:|------|:----:|
| `streetlight/{deviceId}/command` | 云→设备 | 控制指令下发（ON/OFF/DIMMING） | ✅ **已发布** |

---

## 五、数据库表结构

### 5.1 device — 设备台账

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| device_id | VARCHAR(50) | 设备唯一编号 |
| name | VARCHAR(100) | 设备名称 |
| area | VARCHAR(50) | 区域 |
| status | TINYINT | 0-停用 1-在线 2-离线 3-异常 |
| health_score | DECIMAL(5,2) | 健康评分 |
| last_heartbeat_at | DATETIME(3) | 最后心跳时间 |
| latest_data | JSON | 最新遥测快照 |
| **last_manual_at** | DATETIME(3) | **最后手动操作时间（策略引擎锁定用）** |
| enabled | TINYINT(1) | 是否启用 |

### 5.2 lighting_policy — 照明策略

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| name | VARCHAR(100) | 策略名称 |
| policy_type | VARCHAR(20) | THRESHOLD / TIME / SCENE |
| conditions | JSON | 触发条件（如 `{"lux_lt":50}`） |
| action | VARCHAR(50) | 执行动作（ON / OFF / DIMMING(30)） |
| priority | INT | 优先级（1 最高，10 最低） |
| enabled | TINYINT(1) | 是否启用 |

### 5.3 control_command — 控制指令

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| device_id | VARCHAR(50) | 设备ID |
| action | VARCHAR(20) | ON / OFF / DIMMING(70) |
| source | VARCHAR(20) | MANUAL / AUTO |
| status | VARCHAR(20) | SENT / ACKED / FAILED |
| issued_at | DATETIME(3) | 指令下发时间 |
| result_detail | VARCHAR(255) | 执行结果详情（"策略引擎-人车增亮"） |

### 5.4 decision_log — 策略决策日志（新增）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| device_id | VARCHAR(50) | 设备ID |
| input_snapshot | JSON | 决策时的遥测快照 |
| matched_policy | VARCHAR(100) | 命中的策略名称 |
| action_taken | VARCHAR(50) | 执行的动作 |
| result | VARCHAR(20) | MATCH_EXECUTED / NO_MATCH |
| create_time | DATETIME(3) | 记录时间 |

---

## 六、错误码一览

| code | 说明 | 典型场景 |
|:---:|------|---------|
| 200 | 操作成功 | 正常返回 |
| 400 | 参数校验失败 | `action` 为空、`brightness` 超出 0~100 |
| 500 | 业务异常 | 设备不存在、MQTT 下发失败 |
| 500 | 服务器内部错误 | 数据库异常、未捕获的运行时异常 |
