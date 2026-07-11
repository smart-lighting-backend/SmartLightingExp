# 设备 MQTT 鉴权设计方案

> 版本：v1.0 | 日期：2026-07-11

---

## 一、设计目标

为智慧路灯项目实现每台设备的独立 MQTT 鉴权，解决之前"一个密码通天下"的安全隐患。

| 目标 | 说明 |
|------|------|
| 每设备独立凭据 | 用户名 = deviceId，密码 = 出厂编号 + 识别码 |
| 传输加密 | MQTT over SSL (TLS 1.2/1.3) |
| Topic 隔离 | ACL 限制设备只能操作 `streetlight/{自己的deviceId}/#` |
| 集中管理 | 密码在 MySQL 中统一存储，后端 API 管理 |
| 自动同步 | 修改密码后自动同步到 EMQX |

---

## 二、架构设计

```
┌─────────────────────────────────────────────────┐
│                    PostgreSQL/MySQL             │
│  device_credential 表（凭证唯一数据源）           │
│  ├── username (deviceId)                        │
│  ├── password_hash (BCrypt)                     │
│  ├── factory_serial_encrypted (AES-256-CBC)     │
│  └── device_id_code_encrypted (AES-256-CBC)     │
└────────────────────┬────────────────────────────┘
                     │
         ┌───────────┴───────────┐
         │                       │
    [后端 API]              [EMQX 认证]
    增删改查凭证              设备连接时验证
         │                       │
         ▼                       ▼
    PUT /id-code            built_in_database
    修改识别码               plain 密码比对
         │                       │
         └─────── 同步 ──────────┘
              调用 EMQX API
              更新 built_in 用户密码
```

### 密码公式

```
原始密码 = 出厂编号(明文) + 设备识别码(明文)

示例：出厂编号 "AUTO-SL_001" + 识别码 "123456"
     → 密码 "AUTO-SL_001123456"
     → EMQX built_in 存储 "AUTO-SL_001123456"（plain）
     → MySQL 存储 BCrypt("AUTO-SL_001123456")（单向哈希）
```

### 认证流程

```
设备 MQTT CONNECT
    │  username: SL_001
    │  password: AUTO-SL_001123456
    ▼
EMQX :8883 (SSL)
    │
    ▼
认证链: built_in_database
    │  SELECT ... WHERE user_id = 'SL_001'
    │  比对密码（plain）
    ▼
认证通过
    │
    ▼
ACL 检查: ${username} == topic中的deviceId?
    │  例: SL_001 publish streetlight/SL_001/telemetry → 允许
    │      SL_001 publish streetlight/SL_002/telemetry → 拒绝
    ▼
允许连接
```

---

## 三、数据库设计

### device_credential 表

```sql
CREATE TABLE device_credential (
    id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id                  VARCHAR(50) NOT NULL COMMENT '设备ID',
    username                   VARCHAR(50) NOT NULL COMMENT 'MQTT用户名（=deviceId）',
    password_hash              VARCHAR(255) NOT NULL COMMENT 'BCrypt哈希（备用）',
    factory_serial_encrypted   VARCHAR(255) NOT NULL COMMENT '出厂编号(AES加密)',
    device_id_code_encrypted   VARCHAR(255) NOT NULL COMMENT '识别码(AES加密，默认123456)',
    create_time                DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    update_time                DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_device_id (device_id),
    UNIQUE KEY uk_username (username)
);
```

| 列 | 加密 | 用途 |
|----|------|------|
| `username` | 明文 | EMQX 认证查询、后端展示 |
| `password_hash` | BCrypt 单向 | 备用（MySQL 认证器修好后启用） |
| `factory_serial_encrypted` | AES-256-CBC 可逆 | 后端解密用于密码重建 |
| `device_id_code_encrypted` | AES-256-CBC 可逆 | 后端解密用于密码重建 |

> `device` 表不增加任何字段，凭证独立存储在 `device_credential` 中。

---

## 四、加密方案

| 加密对象 | 算法 | 密钥位置 | 说明 |
|---------|------|---------|------|
| MySQL 存储（出厂编号、识别码） | AES-256-CBC | `.env` `DEVICE_AES_KEY` | 可逆加密，运维查看密码时需要解密 |
| EMQX 认证密码 | 明文（TLS 隧道内） | — | TLS 1.3 加密隧道保证传输安全 |
| MySQL 密码哈希 | BCrypt | — | 单向不可逆，防数据库拖库 |

---

## 五、API 设计

| 接口 | 方法 | 权限 | 说明 |
|------|------|------|------|
| `/api/devices/{id}/credentials` | GET | `device:read` | 查看设备 MQTT 连接信息 |
| `/api/devices/{id}/id-code` | PUT | `device:credential` | 修改识别码（自动同步 EMQX） |

### 修改识别码

```json
请求: PUT /api/devices/SL_001/id-code
Body: { "idCode": "789012" }

响应: {
  "code": 200,
  "data": {
    "deviceId": "SL_001",
    "idCode": "789012",
    "newPassword": "AUTO-SL_001789012",
    "message": "密码已更新并同步到 EMQX"
  }
}
```

### 查看凭证

```json
响应: {
  "deviceId": "SL_001",
  "username": "SL_001",
  "password": "AUTO-SL_001123456",
  "broker": "47.96.27.141",
  "port": "8883",
  "protocol": "mqtts",
  "topicPrefix": "streetlight/SL_001"
}
```

---

## 六、权限设计

| 操作 | 权限码 | SUPER_ADMIN | MAINTENANCE |
|------|--------|:---:|:---:|
| 查看凭证 | `device:read` | ✅ | ✅ |
| 修改识别码 | `device:credential` | ✅ | ❌ |
| 创建设备（含出厂编号） | `device:create` | ✅ | ✅ |

---

## 七、EMQX 配置

### 认证链

```
1. built_in_database (priority: high)
   - backend    → 服务账号，全权限
   - dashboard  → 监控订阅
   - SL_001...  → 设备账号，各自密码
   
2. MySQL (priority: low, 暂禁用)
   - 因 EMQX 6.2.1 与 MySQL 8.4 不兼容暂时禁用
   - 升级 EMQX 后可启用
```

### ACL 规则

```erlang
{allow, {username, "backend"}, all, ["streetlight/#", "system/#"]}.
{allow, {username, "dashboard"}, subscribe, ["$SYS/#"]}.
{allow, all, publish, [
    "streetlight/${username}/telemetry",
    "streetlight/${username}/heartbeat",
    "streetlight/${username}/vision/event",
    "streetlight/${username}/voice/event",
    "streetlight/${username}/command/ack"
]}.
{allow, all, subscribe, ["streetlight/${username}/control"]}.
{deny, all}.
```

### SSL 监听器

```yaml
ssl:default:
  bind: "0.0.0.0:8883"
  enable: true
  ssl_options:
    verify: verify_none    # 不验证客户端证书（用密码认证）
    versions: [tlsv1.3, tlsv1.2]
```

---

## 八、级联场景

| 操作 | MySQL device_credential | EMQX built_in | 说明 |
|------|:---:|:---:|------|
| 创建设备 | ✅ 自动生成 | ✅ 初始化器写入 | `DeviceCredentialInitializer` |
| 修改识别码 | ✅ BCrypt + AES 更新 | ✅ API 同步 | 密码 = 出厂编号 + 新识别码 |
| 修改设备 ID | ❌ 不支持 | ❌ 不支持 | 级联影响太大（MQTT topic/TDengine 子表） |
| 删除设备 | ✅ 级联删除 | ✅ API 删除 | `deleteByDeviceId()` |
| 停用设备 | 保留 | 保留 | 重新启用后仍可用 |

---

## 九、当前局限与后续改进

| 局限 | 改进方向 |
|------|---------|
| 使用 built_in 存储密码（非单源） | 启用 MySQL 认证器或 HTTP Auth，使 MySQL 成为唯一数据源 |
| SSL 证书为自签名 | 生产环境使用 Let's Encrypt 或购买的 CA 证书 |
| MockDataGenerator 每设备临时连接 | 生产环境使用真实设备，连接数可控 |
| EMQX 6.2.1 MySQL 驱动 bug | 升级 EMQX 到 6.3+ |
| 密码修改未通知已连接设备 | 可以实现 EMQX 强制踢下线 + 设备自动重连 |
