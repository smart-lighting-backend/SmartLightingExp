# 设备 MQTT 鉴权方案实施文档

> 版本: v1.0  
> 日期: 2026-07-11  
> 方案: 路 C — BCrypt 哈希 + TLS 传输加密 + EMQX MySQL 认证

---

## 一、背景与目标

### 1.1 当前问题

| 问题 | 现状 | 风险 |
|------|------|------|
| 设备无独立身份 | 后端 + 前端 + 模拟器共用 `backend/123456` | 任一泄露影响全局 |
| 无 Topic 隔离 | 任意客户端可发布到任意设备 Topic | 设备可伪装其他设备 |
| MQTT 明文传输 | `tcp://47.96.27.141:1883` 无加密 | 网络嗅探可直接获取密码 |
| 无设备凭证表 | `device` 表无鉴权相关字段 | 无法管理设备凭据 |

### 1.2 目标

1. **每设备独立凭据**：用户名 = `deviceId`，密码 = `出厂编号 + 设备识别码` 拼接
2. **BCrypt 哈希存储**：密码不可逆，数据库拖库无法还原
3. **TLS 传输加密**：设备 → EMQX 走 SSL 加密通道
4. **Topic ACL 隔离**：设备只能操作自己 `streetlight/{deviceId}/#` 的 Topic
5. **不影响现有功能**：`backend` 服务账号保持不变，后端订阅/发布不受影响

---

## 二、前置评估

### 2.1 EMQX 环境

| 项目 | 实际值 | 结论 |
|------|--------|------|
| 版本 | **EMQX 6.2.1 Enterprise Edition** | ✅ 支持 MySQL 认证 + BCrypt + 认证链 + ACL 变量 |
| SSL 监听器 | `:8883` 已运行，证书已配置 | ✅ 无需重新生成证书 |
| WSS 监听器 | `:8084` 已运行 | ✅ 前端改地址即可 |
| 认证链 | 6.x 原生支持多认证器串联 | ✅ built_in → MySQL 链式认证 |
| ACL 变量 | 支持 `${username}` 占位符 | ✅ 设备 Topic 隔离 |

### 2.2 RDS 连通性

- RDS 白名单：`0.0.0.0/0` → 任意 IP 可连接
- EMQX 容器在 ECS Docker，出站经 ECS 公网 IP → RDS
- **结论**：✅ EMQX 可以直接连接 RDS MySQL

### 2.3 设备 ID 下划线兼容性评估

设备 ID 统一使用下划线格式（如 `SL_001`），对各组件影响：

| 组件 | 使用场景 | 影响 |
|------|---------|------|
| **MQTT Username** | `SL_001` 作为 CONNECT 报文的 username | ✅ MQTT 规范允许任意 UTF-8 字符串，下划线合法 |
| **EMQX MySQL Auth SQL** | `WHERE username = '${username}'` — 精确匹配 `=` 非 `LIKE` | ✅ MySQL 中 `=` 不把 `_` 当通配符，无影响 |
| **EMQX ACL 规则** | `"streetlight/${username}/telemetry"` → `"streetlight/SL_001/telemetry"` | ✅ 下划线是 MQTT Topic 合法字符 |
| **EMQX 内置数据库** | `backend` 用户在 built_in 中，设备用户在 MySQL 中 | ✅ 两者用不同认证器，username 不冲突 |
| **后端 MqttSubscriber** | `topic.split("/")[1]` 提取 deviceId | ✅ 纯字符串分割，与 ID 格式无关 |
| **TDengine 子表** | 表名 `t_SL_001` | ✅ 之前已统一为下划线格式 |

> **历史教训**：之前 EMQX 6.2.1 规则引擎中 `payload.deviceId` 始终为 null（不是下划线问题，是 EMQX JSON 解析 bug），已通过 `nth(2, split(topic, '/'))` 从 Topic 提取解决。**本次鉴权改造不涉及规则引擎，不受此影响。**

### 2.4 认证链兼容性

当前 EMQX 只有 1 个认证器：`password_based:built_in_database`（存储 `backend` 用户）。

新增 MySQL 认证器后形成认证链：

```
客户端 CONNECT
    │
    ▼
认证器1: built_in_database (保持不变)
    │  backend 用户 → allow ✓
    │  未找到 → 继续到认证器2
    ▼
认证器2: MySQL (新增)
    │  device_credential 表中有匹配 → BCrypt 验证 → allow/deny
    │  未找到 → 继续
    ▼
无匹配 → 拒绝连接
```

> **认证链特性**：EMQX 6.x 按顺序尝试认证器。若某认证器返回 `not_found`（用户不在该数据源中），则继续下一个；若返回 `success` 或 `bad_password`，则停止。

---

## 三、数据库设计

### 3.1 新表：`device_credential`

```sql
CREATE TABLE device_credential (
    id                         BIGINT          AUTO_INCREMENT PRIMARY KEY,
    device_id                  VARCHAR(50)     NOT NULL COMMENT '设备ID，关联device表(device_id)',
    username                   VARCHAR(50)     NOT NULL COMMENT 'MQTT用户名（= deviceId，如SL_001）',
    password_hash              VARCHAR(255)    NOT NULL COMMENT 'BCrypt密码哈希（EMQX直接读取此列做验证）',
    factory_serial_encrypted   VARCHAR(255)    NOT NULL COMMENT '出厂编号(AES-256-CBC加密存储)',
    device_id_code_encrypted   VARCHAR(255)    NOT NULL COMMENT '设备识别码(AES-256-CBC加密，默认123456)',
    create_time                DATETIME(3)     DEFAULT CURRENT_TIMESTAMP(3),
    update_time                DATETIME(3)     DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_device_id (device_id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备MQTT鉴权凭证表';
```

### 3.2 列说明

| 列 | 存储内容示例 | 加密方式 | 谁读取 |
|----|------------|---------|--------|
| `device_id` | `SL_001` | 明文 | 后端关联查询 |
| `username` | `SL_001` | 明文 | EMQX MySQL Auth SQL 查询 |
| `password_hash` | `$2a$10$xjK8qW3m...` | **BCrypt（单向）** | **EMQX 直接读此列做 BCrypt 验证** |
| `factory_serial_encrypted` | `AES("AAA-FACTORY-001")` | **AES-256-CBC（可逆）** | 后端解密后用于密码重建/展示 |
| `device_id_code_encrypted` | `AES("123456")` | **AES-256-CBC（可逆）** | 后端解密后用于密码重建/展示 |

### 3.3 `device` 表不改动

所有鉴权凭证存在新表 `device_credential`，`device` 表不增加任何字段。两表通过 `device_id` 关联。

---

## 四、密码生成与验证流程

### 4.1 密码生成公式

```
原始密码 = 出厂编号(明文) + 设备识别码(明文)

例如: 出厂编号 "AAA-FACTORY-001" + 识别码 "123456"
     → 原始密码 "AAA-FACTORY-001123456"
     → BCrypt("AAA-FACTORY-001123456") → "$2a$10$..."
     → 写入 password_hash
```

### 4.2 设备注册流程（创建设备时）

```
1. 前端输入: 出厂编号 = "AAA-FACTORY-001"
2. 系统默认: 设备识别码 = "123456"
3. 后端处理:
   a. username = deviceId (如 "SL_001")
   b. 拼接原始密码: factorySerial + "123456"
   c. BCrypt 哈希 → password_hash
   d. AES-256-CBC("AAA-FACTORY-001") → factory_serial_encrypted
   e. AES-256-CBC("123456") → device_id_code_encrypted
   f. 写入 device_credential 表
4. 运维查看凭证 → 获取原始密码 "AAA-FACTORY-001123456"
5. 设备烧录: username=SL_001, password=AAA-FACTORY-001123456
```

### 4.3 设备 MQTT 连接验证流程（运行时）

```
设备 ──TLS 1.3 握手(加密通道建立)──▶ EMQX (:8883)
         │
         └── CONNECT {username:"SL_001", password:"AAA-FACTORY-001123456"}
              (密码在 TLS 隧道内是明文，但网络传输层已加密)

EMQX:
  1. 认证器1 (built_in): 查 "SL_001" → 不存在 → 继续
  2. 认证器2 (MySQL):  执行 SQL:
     SELECT password_hash FROM device_credential
     WHERE username = 'SL_001' LIMIT 1
     → 得到 "$2a$10$xjK8qW3m..."
  3. BCrypt.verify("AAA-FACTORY-001123456", "$2a$10$xjK8qW3m...")
     → true ✓
  4. ACL 检查:
     Topic streetlight/SL_001/telemetry
     ${username} = SL_001
     Topic 中的 deviceId "SL_001" == username "SL_001" → 允许
```

---

## 五、EMQX 配置变更

### 5.1 新增 MySQL 认证器

访问 EMQX Dashboard (`http://47.96.27.141:18083`) → 访问控制 → 认证 → 创建：

```
认证方式:   Password-Based
数据源:     MySQL
启用:       true

数据库配置:
  服务器:   rm-bp1vhdjtj6xp496p09o.mysql.rds.aliyuncs.com:3306
  数据库:   smart_lighting_db
  用户名:   app_user
  密码:     (从 .env 获取 DB_PASSWORD)

密码哈希算法: bcrypt

SQL:
  SELECT password_hash FROM device_credential WHERE username = '${username}' LIMIT 1
```

> **重要**：新增认证器排在 `built_in_database` **之后**（认证链顺序：built_in → MySQL）。

### 5.2 ACL 改造

将 ACL 从当前的 legacy 模式改为精确设备隔离规则：

```erlang
%% ============================================================
%% 设备 MQTT 鉴权 ACL 规则
%% ============================================================

%% ---- 服务账号（全权限） ----
{allow, {username, "backend"}, all, ["streetlight/#", "system/#"]}.

%% ---- Dashboard 系统监控 ----
{allow, {username, "dashboard"}, subscribe, ["$SYS/#"]}.

%% ---- 设备账号（${username} 变量 = deviceId） ----
%% 设备可以 PUBLISH 到自己的数据上报主题
{allow, all, publish, [
    "streetlight/${username}/telemetry",
    "streetlight/${username}/heartbeat",
    "streetlight/${username}/vision/event",
    "streetlight/${username}/voice/event",
    "streetlight/${username}/command/ack"
]}.

%% 设备可以 SUBSCRIBE 自己的控制指令主题
{allow, all, subscribe, [
    "streetlight/${username}/control"
]}.

%% ---- 兜底：拒绝其他所有 ----
{deny, all}.
```

> **需要**：将 `authorization.no_match` 设置为 `deny`。

### 5.3 SSL 监听器确认

已有 SSL 监听器（`ssl:default`），确认以下配置：

```yaml
ssl:default:
  bind: "0.0.0.0:8883"
  enable: true
  ssl_options:
    verify: verify_none     # 不验证客户端证书（用密码认证）
    fail_if_no_peer_cert: false
    versions: [tlsv1.3, tlsv1.2]
```

> 当前 EMQX SSL 证书为自签名证书，设备端连接时需要信任该证书或跳过证书校验（实验项目可接受）。

---

## 六、后端代码改造

### 6.1 新增文件清单

| 文件 | 路径 | 说明 |
|------|------|------|
| `DeviceCredential.java` | `entity/` | 新表实体 |
| `DeviceCredentialMapper.java` | `mapper/` | MyBatis-Plus Mapper |
| `device_credential` DDL | `database/02_device_credential.sql` | 建表 SQL |
| `AesUtil.java` | `util/` | AES-256-CBC 加解密工具 |
| `DeviceCredentialService.java` | `service/` | 凭证服务接口 |

### 6.2 修改文件清单

| 文件 | 改动内容 |
|------|---------|
| `.env` | 新增 `DEVICE_AES_KEY=<32字节密钥>` |
| `application.yaml` | 新增 `device.aes-key: ${DEVICE_AES_KEY}` |
| `DeviceController.java` | `create()`/`batchCreate()`/`batchImport()` 增加出厂编号处理 → 调用凭证服务生成记录 |
| `DeviceController.java` | 新增 `GET /api/devices/{deviceId}/credentials` 凭证查询接口 |
| `DeviceCreateRequest.java` | 新增 `factorySerial` 字段（可选，兼容旧接口） |
| `MqttConfig.java` | `tcp://` → `ssl://`, 端口 `1883` → `8883`，增加 SSL SocketFactory |
| `MockDataGenerator.java` | 每设备独立 MQTT SSL 连接，用各自 username/password 发布 |

### 6.3 核心代码逻辑

#### 6.3.1 AES 加解密（AesUtil.java）

```java
public class AesUtil {
    private final SecretKeySpec keySpec;
    private final IvParameterSpec ivSpec;
    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";

    public AesUtil(String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        // 前 16 字节作为 IV
        this.ivSpec = new IvParameterSpec(Arrays.copyOfRange(keyBytes, 0, 16));
        this.keySpec = new SecretKeySpec(Arrays.copyOfRange(keyBytes, 16, 48), "AES");
    }

    public String encrypt(String plainText) { /* AES-256-CBC 加密 → Base64 */ }
    public String decrypt(String cipherText) { /* Base64 解码 → AES-256-CBC 解密 */ }
}
```

#### 6.3.2 凭证生成（DeviceCredentialService）

```java
@Service
public class DeviceCredentialService {

    private static final String DEFAULT_ID_CODE = "123456";

    /**
     * 创建设备时调用：生成 BCrypt 密码哈希 + AES 加密存储
     */
    public DeviceCredential createCredential(String deviceId, String factorySerialPlain) {
        // 1. 解密存储所需
        String idCode = DEFAULT_ID_CODE;
        String plainPassword = factorySerialPlain + idCode;

        // 2. 构建凭证记录
        DeviceCredential cred = new DeviceCredential();
        cred.setDeviceId(deviceId);
        cred.setUsername(deviceId);  // 用户名 = 设备ID
        cred.setPasswordHash(bCryptPasswordEncoder.encode(plainPassword));  // BCrypt
        cred.setFactorySerialEncrypted(aesUtil.encrypt(factorySerialPlain)); // AES
        cred.setDeviceIdCodeEncrypted(aesUtil.encrypt(idCode));              // AES
        credentialMapper.insert(cred);
        return cred;
    }

    /**
     * 获取设备原始密码（供运维查看、设备烧录）
     */
    public String getPlainPassword(String deviceId) {
        DeviceCredential cred = credentialMapper.selectOne(
            new LambdaQueryWrapper<DeviceCredential>()
                .eq(DeviceCredential::getDeviceId, deviceId));
        if (cred == null) return null;
        String factorySerial = aesUtil.decrypt(cred.getFactorySerialEncrypted());
        String idCode = aesUtil.decrypt(cred.getDeviceIdCodeEncrypted());
        return factorySerial + idCode;
    }
}
```

#### 6.3.3 MockDataGenerator 改造

```java
/**
 * 为指定设备创建临时 MQTT SSL 连接（用设备自己的凭证）。
 */
private MqttClient createDeviceClient(String deviceId, String plainPassword) throws MqttException {
    String clientId = "mock-" + deviceId + "-" + System.currentTimeMillis();
    MqttClient client = new MqttClient(
        "ssl://47.96.27.141:8883", clientId, new MemoryPersistence());

    MqttConnectOptions opts = new MqttConnectOptions();
    opts.setUserName(deviceId);
    opts.setPassword(plainPassword.toCharArray());
    opts.setCleanSession(true);
    opts.setConnectionTimeout(5);
    opts.setKeepAliveInterval(15);

    // SSL：信任自签名证书
    opts.setSocketFactory(createTrustAllSocketFactory());

    client.connect(opts);
    return client;
}

private SSLSocketFactory createTrustAllSocketFactory() {
    TrustManager[] trustAll = new TrustManager[]{
        new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }
    };
    SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
    sslContext.init(null, trustAll, new SecureRandom());
    return sslContext.getSocketFactory();
}
```

> **注意**：`createTrustAllSocketFactory()` 跳过证书校验仅适用于实验环境。生产环境应导入正确的 CA 证书。

#### 6.3.4 MqttConfig 改造

```java
// 修改 broker URL
// 原: tcp://47.96.27.141:1883
// 新: ssl://47.96.27.141:8883

// 增加 SSL SocketFactory（信任自签名证书）
SSLSocketFactory sslFactory = createTrustAllSocketFactory();
options.setSocketFactory(sslFactory);
```

---

## 七、前端改造

### 7.1 创建设备增加出厂编号输入

- `Devices.vue` 创建设备对话框：增加"出厂编号"输入框（可选字段）
- `BatchImport.vue` 批量导入：Excel 模板增加"出厂编号"列
- API 请求 `deviceCreateRequest` 增加 `factorySerial` 字段

### 7.2 WebSocket 改为 WSS

`useMqtt.js` 第 16-17 行：

```javascript
// 原
const BROKER_URL = 'ws://47.96.27.141:8083/mqtt'

// 改
const BROKER_URL = 'wss://47.96.27.141:8084/mqtt'
```

### 7.3 设备详情页增加凭证查看

`DeviceDetail.vue` 增加"查看凭证"按钮（有 `device:read` 权限可见），点击弹窗显示：
- MQTT 连接地址、用户名、密码（明文）
- 二维码（方便移动端扫码配置）

---

## 八、影响评估

### 8.1 对现有功能的影响

| 功能模块 | 影响评估 | 说明 |
|---------|---------|------|
| **设备 CRUD** | ⚠️ 轻微影响 | 创建接口增加出厂编号参数（可选），不传则跳过凭证生成 |
| **遥测数据采集** | ✅ 无影响 | MockDataGenerator 仍发布到相同 Topic，MqttSubscriber 订阅逻辑不变 |
| **心跳离线检测** | ✅ 无影响 | HeartbeatMonitorTask 读 `device.lastHeartbeatAt`，不受鉴权影响 |
| **设备控制** | ✅ 无影响 | MqttPublisher 用 `backend` 账号发布，ACL 允许 backend 全权限 |
| **AI 自动控制** | ✅ 无影响 | DecisionEngine 在遥测处理流程中触发，流程不变 |
| **告警** | ✅ 无影响 | 告警产生和恢复逻辑不变 |
| **能耗统计** | ✅ 无影响 | EnergyCalcTask 定时计算，不依赖 MQTT |
| **Dashboard** | ✅ 无影响 | 统计查询不涉及 MQTT 鉴权 |
| **前端 WebSocket** | ⚠️ 轻微影响 | 改 `ws://` → `wss://`，端口 8083 → 8084 |
| **移动端** | ✅ 无影响 | 移动端当前无 MQTT，纯 REST 不受影响 |
| **TDengine 数据写入** | ✅ 无影响 | EMQX 规则引擎桥接不受认证变更影响 |

### 8.2 不影响的总结

**不改动的组件**：
- `MqttSubscriber.java` — 订阅和消息路由逻辑完全不变
- `MqttPublisher.java` — 发布控制指令逻辑不变（仍用 backend 账号）
- `device` 表 — 不加字段
- `HeartbeatMonitorTask` — 心跳检测逻辑不变
- `EnergyCalcTask` / `HealthScoreTask` — 定时任务不变
- `DecisionEngine` / `ConditionEvaluator` — 决策引擎不变
- 移动端 Android 项目 — 暂不改动

### 8.3 风险点

| 风险 | 概率 | 缓解措施 |
|------|------|---------|
| MockDataGenerator SSL 连接性能下降 | 中 | 每设备临时连接在 60s 周期内完成，实测达标后再合入 |
| 前端 WSS 证书浏览器拦截 | 低 | 自签名证书可能需用户在浏览器中信任，或配置 nginx 反向代理 |
| 后端 MqttConfig SSL 连接失败 | 低 | 保留 TCP 1883 作为回退，配置开关 |
| 设备旧 ID 遗留问题 | 低 | 目前全部为 `SL_` 下划线格式，无遗留连字符 ID |
| EMQX MySQL 认证器配置错误导致设备无法连接 | 中 | 认证链设计，built_in 在前不受影响；可随时禁用 MySQL 认证器回退 |

---

## 九、实施步骤

### 第一阶段：数据库 + 后端基础代码（预计 1 小时）

| 步骤 | 内容 | 验证方式 |
|------|------|---------|
| 1.1 | 执行建表 SQL（`database/02_device_credential.sql`） | MySQL 检查表存在 |
| 1.2 | `.env` 增加 `DEVICE_AES_KEY`（生成 32 字节随机密钥） | echo 验证 |
| 1.3 | 新增 `AesUtil.java`，编写单元测试验证加解密 | 单元测试通过 |
| 1.4 | 新增 `DeviceCredential.java` 实体 + Mapper | 编译通过 |
| 1.5 | 新增 `DeviceCredentialService`（BCrypt + AES 集成） | 单元测试：create + getPlainPassword 一致性 |
| 1.6 | 修改 `DeviceController.create()` 增加出厂编号处理 | Postman 测试创建带 `factorySerial` 的设备 |
| 1.7 | 修改 `DeviceController.batchCreate()` / `batchImport()` | Postman 批量创建测试 |
| 1.8 | 新增 `GET /api/devices/{deviceId}/credentials` 接口 | Postman 验证返回正确原始密码 |
| 1.9 | `application.yaml` 增加 `device.aes-key` 配置 | 启动无报错 |

### 第二阶段：EMQX 配置（预计 30 分钟）

| 步骤 | 内容 | 验证方式 |
|------|------|---------|
| 2.1 | EMQX Dashboard 新增 MySQL 认证器 | 认证器列表中出现 MySQL |
| 2.2 | 验证认证链顺序（built_in → MySQL） | 查看认证器列表顺序 |
| 2.3 | 更新 ACL 文件为设备隔离规则 | EMQX API 确认 ACL 已更新 |
| 2.4 | 设置 `authorization.no_match = deny` | Dashboard 确认 |
| 2.5 | 用 MQTTX 工具模拟设备 `SL_001` 通过 SSL 连接测试 | 连接成功，Topic 权限正确 |

### 第三阶段：后端 MQTT 客户端改造（预计 30 分钟）

| 步骤 | 内容 | 验证方式 |
|------|------|---------|
| 3.1 | `MqttConfig` 改 `ssl://` 并增加 SSL SocketFactory | 后端启动，日志显示 MQTT connected |
| 3.2 | `MqttConfig` 保留 TCP 回退开关（可选） | 可通过配置切换 |
| 3.3 | `MockDataGenerator` 改为每设备独立 SSL 连接 | 日志显示各设备成功发布遥测 |
| 3.4 | Dashboard 查看设备在线状态和数据更新 | 仪表盘正常 |

### 第四阶段：前端改造（预计 30 分钟）

| 步骤 | 内容 | 验证方式 |
|------|------|---------|
| 4.1 | `useMqtt.js` 改 `wss://` :8084 | 控制台显示 MQTT 已连接 |
| 4.2 | `Devices.vue` 创建设备对话框增加出厂编号输入 | 创建设备后数据正常 |
| 4.3 | `DeviceDetail.vue` 增加凭证查看按钮 | 弹窗显示正确凭证 |
| 4.4 | `BatchImport.vue` 支持出厂编号列 | 批量导入正常 |

### 第五阶段：联调测试（预计 30 分钟）

| 步骤 | 内容 | 验证方式 |
|------|------|---------|
| 5.1 | 全链路测试：创建设备 → 模拟器发送数据 → 后端接收 → Dashboard 展示 | 端到端正常 |
| 5.2 | 用 MQTTX 模拟恶意设备尝试发布到其他设备 Topic | ACL 拒绝 |
| 5.3 | 测试设备删除/停用后 MQTT 连接被拒绝 | 连接失败 |
| 5.4 | 测试心跳检测、告警、控制指令仍正常 | 功能正常 |

---

## 十、回滚方案

若出现问题，按以下顺序回滚：

| 优先级 | 操作 | 恢复内容 |
|--------|------|---------|
| 1 | EMQX Dashboard 禁用 MySQL 认证器 | 设备认证回退到仅有 built_in |
| 2 | EMQX ACL 改回 `{allow, {security_profile, legacy}}` | 恢复宽松 ACL |
| 3 | `MqttConfig` 改回 `tcp://:1883` | 后端恢复 TCP 连接 |
| 4 | `MockDataGenerator` 恢复使用单一 MqttClient | 模拟数据发布恢复 |
| 5 | `useMqtt.js` 改回 `ws://:8083` | 前端恢复 WebSocket |

> 回滚不影响数据库（`device_credential` 表保留），不会丢失数据。

---

## 十一、附录

### A. 生成 AES 密钥

```bash
# 生成 32 字节随机密钥并 Base64 编码
openssl rand -base64 32
# 示例输出: dGhpcyBpcyBhIDMyIGJ5dGUgcmFuZG9tIGtleSBmb3IgQUVT
```

将输出写入 `.env`：
```
DEVICE_AES_KEY=dGhpcyBpcyBhIDMyIGJ5dGUgcmFuZG9tIGtleSBmb3IgQUVT
```

### B. MQTTX 连接测试命令

```bash
# 安装 MQTTX CLI（如未安装）
npm install -g mqttx

# 模拟设备 SL_001 连接（SSL，信任自签名证书）
mqttx conn --hostname 47.96.27.141 --port 8883 \
  --protocol mqtts --insecure \
  --client-id test-SL_001 --username SL_001 \
  --password "AAA-FACTORY-001123456"

# 发布遥测数据
mqttx pub --topic streetlight/SL_001/telemetry \
  --qos 1 --message '{"illuminance":800,"temperature":25.5,"collectedAt":"2026-07-11T10:00:00"}'

# 尝试越权发布到 SL_002 的 Topic（预期被 ACL 拒绝）
mqttx pub --topic streetlight/SL_002/telemetry --qos 1 --message '{}'
```

### C. BCrypt 密码验证（后端工具方法）

```java
// Spring Security Crypto 自带 BCrypt
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

// 加密
String hash = encoder.encode("AAA-FACTORY-001123456");
// → "$2a$10$xjK8qW3mVxY..."

// 验证
boolean match = encoder.matches("AAA-FACTORY-001123456", hash);
// → true
```
