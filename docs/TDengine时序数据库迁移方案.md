# TDengine + EMQX 规则引擎 — 时序数据迁移计划

## 背景

智慧路灯系统当前三张流水表（`telemetry`、`vision_event`、`voice_event`）存储在 MySQL 中。项目即将接入真实硬件，数据产生频率将从模拟的每 5 分钟一次提升到每 10-30 秒一次。MySQL 的写入性能和存储空间将成为瓶颈。

目标：将这三张表迁移到 TDengine 时序数据库，利用 EMQX 规则引擎实现数据直写（绕过 Spring Boot 后端），后端只做业务逻辑。

## 涉及文件清单

### 代码改动

| 操作 | 文件 | 说明 |
|------|------|------|
| 新建 | `config/TdengineConfig.java` | TDengine 连接池配置 |
| 新建 | `config/TdengineProperties.java` | TDengine 连接参数配置类 |
| 新建 | `tdengine/TdengineTemplate.java` | 通用 JDBC 操作模板（insert、query、分页） |
| 新建 | `tdengine/TelemetryDao.java` | 遥测 DAO |
| 新建 | `tdengine/VisionEventDao.java` | 视觉事件 DAO |
| 新建 | `tdengine/VoiceEventDao.java` | 语音事件 DAO |
| 修改 | `mqtt/MqttSubscriber.java` | 删除 telemetry/vision/voice 的 MySQL 写入代码 |
| 修改 | `engine/DecisionEngine.java` | 删除 voiceEvent 的 MySQL 写入代码 |
| 修改 | `controller/TelemetryController.java` | `getHistory` 查询源从 MySQL → TDengine |
| 修改 | `controller/VisionEventController.java` | `page` + `byDevice` → TDengine |
| 修改 | `controller/VoiceEventController.java` | `page` + `byDevice` → TDengine |
| 修改 | `task/HealthScoreTask.java` | `calcCommunicationScore` → TDengine |
| 修改 | `task/EdgeNodeSimulator.java` | 最新遥测查询 → TDengine |
| 修改 | `pom.xml` | 添加 TDengine JDBC 驱动 |
| 修改 | `application.yaml` | 添加 TDengine 连接配置 |
| 修改 | `.env` | 添加 TDengine 敏感配置 |
| 删除 | `mapper/TelemetryMapper.java` | （或保留但不注入，作为 MySQL 备份读路径） |

### 不改的文件

| 文件 | 原因 |
|------|------|
| `entity/Telemetry.java` | 保留，JDBC 映射仍需要 |
| `entity/VisionEvent.java` | 同上 |
| `entity/VoiceEvent.java` | 同上 |
| `controller/DeviceController.java` | `getLatest` 读 `device.latestData`（MySQL JSON 快照），不查流水表；`calcResponse` 查 `control_command`，不涉及这三张表 |
| `service/AssistantService.java` | `diagnose()` 读 `device.latestData` + `alarm_record` + `control_command`，不直接查这三张流水表 |
| `task/HeartbeatMonitorTask.java` | 只查 `device` 表，不涉及流水表 |
| `task/EnergyCalcTask.java` | 只查 `control_command`，不涉及 |
| 所有 Service 接口和实现 | 通过 MyBatis-Plus IService 接口，DAO 层切换对其透明 |
| 所有 DTO | 没变 |
| `database/` 目录 | MySQL 建表脚本保留（作为 Schema 参考和备份恢复） |

### 不需要改动

- **前端代码**：零改动。Controller 返回的 `Result<IPage<Entity>>` 结构不变，Entity 字段不变，接口路径不变。
- **权限注解**：所有 `@RequirePermission` 保持不变。

## 架构对比

```
改动前:
Device → MQTT → EMQX → MqttSubscriber → telemetryMapper.insert(MySQL)
                                         → decisionEngine.evaluate()
                                         → device 状态更新

改动后:
Device → MQTT → EMQX Rule Engine → TDengine（直写，1ms 内）
              └→ MqttSubscriber → 跳过写库
                                → decisionEngine.evaluate()
                                → device 状态更新（MySQL）
```

## 实施步骤

### 步骤 1：部署 TDengine

在云服务器上 Docker 部署：

```bash
docker run -d --name tdengine \
  --restart always \
  -p 6030:6030 -p 6041:6041 \
  -v /data/tdengine:/var/lib/taos \
  -e TAOS_FQDN=你的服务器内网IP \
  tdengine/tdengine:3.3.2.0
```

创建数据库和超级表：

```sql
CREATE DATABASE smart_lighting KEEP 365 DAYS 10;
USE smart_lighting;

-- 遥测超级表
CREATE STABLE telemetry (
    ts            TIMESTAMP,
    illuminance   DOUBLE,
    temperature   DOUBLE,
    humidity      DOUBLE,
    pm25          DOUBLE,
    aqi           INT,
    pir           TINYINT,
    traffic_flow  INT
) TAGS (device_id VARCHAR(50));

-- 视觉事件超级表
CREATE STABLE vision_event (
    ts            TIMESTAMP,
    event_type    VARCHAR(30),
    confidence    DOUBLE,
    snapshot_ref  VARCHAR(255)
) TAGS (device_id VARCHAR(50));

-- 语音事件超级表
CREATE STABLE voice_event (
    ts            TIMESTAMP,
    type          VARCHAR(20),
    content       VARCHAR(500),
    source        VARCHAR(20)
) TAGS (device_id VARCHAR(50));
```

### 步骤 2：EMQX 规则引擎配置

在 EMQX Dashboard（`http://47.96.27.141:18083`）中配置：

**Step 2a：创建 TDengine 连接器**

「集成」→「连接器」→「创建」→ TDengine：

| 参数 | 值 |
|------|-----|
| 服务器地址 | `127.0.0.1:6041`（或内网 IP） |
| 数据库 | `smart_lighting` |
| 用户名 | `root` |
| 密码 | `taosdata` |

**Step 2b：创建遥测数据桥接规则**

规则 SQL：
```sql
SELECT
  payload.illuminance   as illuminance,
  payload.temperature   as temperature,
  payload.humidity      as humidity,
  payload.pm25          as pm25,
  payload.aqi           as aqi,
  payload.pir           as pir,
  payload.trafficFlow   as traffic_flow,
  payload.collectedAt   as ts,
  topic                 as topic
FROM
  "streetlight/+/telemetry"
```

添加动作 → 数据桥接 → TDengine，SQL 模板：
```sql
INSERT INTO t_${substring(topic, 13, -10)}
USING telemetry TAGS ('${substring(topic, 13, -10)}')
VALUES (${ts}, ${illuminance}, ${temperature}, ${humidity}, ${pm25}, ${aqi}, ${pir}, ${traffic_flow})
```

**Step 2c：创建视觉事件桥接规则**

规则 SQL：
```sql
SELECT
  payload.eventType    as event_type,
  payload.confidence   as confidence,
  payload.snapshotRef  as snapshot_ref,
  payload.occurredAt   as ts,
  topic                as topic
FROM
  "streetlight/+/vision/event"
```

动作 SQL：
```sql
INSERT INTO v_${substring(topic, 13, -13)}
USING vision_event TAGS ('${substring(topic, 13, -13)}')
VALUES (${ts}, '${event_type}', ${confidence}, '${snapshot_ref}')
```

**Step 2d：创建语音事件桥接规则**

规则 SQL：
```sql
SELECT
  payload.type         as type,
  payload.content      as content,
  payload.source       as source,
  payload.occurredAt   as ts,
  topic                as topic
FROM
  "streetlight/+/voice/event"
```

动作 SQL：
```sql
INSERT INTO v_${substring(topic, 13, -13)}
USING voice_event TAGS ('${substring(topic, 13, -13)}')
VALUES (${ts}, '${type}', '${content}', '${source}')
```

### 步骤 3：后端代码改动

#### 3.1 pom.xml — 添加 TDengine JDBC 驱动

```xml
<dependency>
    <groupId>com.taosdata.jdbc</groupId>
    <artifactId>taos-jdbcdriver</artifactId>
    <version>3.3.2</version>
</dependency>
```

#### 3.2 application.yaml — 添加配置

```yaml
tdengine:
  url: jdbc:TAOS://${TDENGINE_HOST:127.0.0.1}:6030/smart_lighting
  username: ${TDENGINE_USER:root}
  password: ${TDENGINE_PASSWORD:taosdata}
```

`.env` 文件添加：
```
TDENGINE_HOST=127.0.0.1
TDENGINE_USER=root
TDENGINE_PASSWORD=taosdata
```

#### 3.3 新建 config/TdengineProperties.java

```java
@ConfigurationProperties(prefix = "tdengine")
public record TdengineProperties(String url, String username, String password) {}
```

#### 3.4 新建 config/TdengineConfig.java

配置 HikariCP 连接池（TDengine 兼容 JDBC 标准）：

```java
@Configuration
public class TdengineConfig {
    @Bean
    public DataSource tdengineDataSource(TdengineProperties props) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(props.url());
        config.setUsername(props.username());
        config.setPassword(props.password());
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10000);
        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate tdengineJdbcTemplate(DataSource tdengineDataSource) {
        return new JdbcTemplate(tdengineDataSource);
    }
}
```

#### 3.5 新建 tdengine/TdengineTemplate.java

封装 TDengine 操作，提供统一的 insert/query 接口。核心方法：

```java
@Component
public class TdengineTemplate {
    // insert(entity) — 自动创建子表 + 插入
    // query(sql, params, rowMapper) — 通用查询
    // latest(deviceId, table) — 获取设备最新一条数据
    // page(sql, page, size) — 分页查询
}
```

TDengine 的 `INSERT` 语法：
```sql
INSERT INTO t_{deviceId} USING telemetry TAGS ('{deviceId}')
VALUES ('{ts}', {illuminance}, {temperature}, ...)
```

查询语法与 MySQL 几乎一致：
```sql
SELECT * FROM telemetry WHERE device_id = 'SL-001'
AND ts >= '2025-01-01' AND ts <= '2025-01-02'
ORDER BY ts DESC LIMIT 0, 20
```

#### 3.6 新建三个 DAO 类

`tdengine/TelemetryDao.java`、`tdengine/VisionEventDao.java`、`tdengine/VoiceEventDao.java`

每个 DAO 封装对应超级表的插入和查询操作。不使用 MyBatis-Plus（TDengine 不是它的目标数据库），直接用 JdbcTemplate。

#### 3.7 修改 MqttSubscriber.java

删除以下三行：
```java
telemetryMapper.insert(telemetry);    // 第 112 行
visionEventMapper.insert(ve);         // 第 64 行
voiceEventMapper.insert(vo);          // 第 70 行
```

遥测处理分支改为：
```java
// 遥测：EMQX 已直写 TDengine，后端只做业务逻辑
executor.submit(() -> {
    Telemetry telemetry = objectMapper.readValue(telemetryJson, Telemetry.class);
    // 更新设备心跳 + latestData 快照 + 状态
    deviceMapper.update(null, updateWrapper);
    // 触发 AI 策略决策
    decisionEngine.evaluate(telemetryDeviceId, telemetry);
    // 恢复离线告警
    alarmRecordService.resolveOfflineAlarm(telemetryDeviceId);
});
```

心跳和 ACK 处理保留不变（它们是 device 和 control_command 的更新，仍然是 MySQL）。

#### 3.8 修改 DecisionEngine.java

删除第 166 行的 `voiceEventMapper.insert(ve)`。策略触发的语音告警改为通过 TDengine DAO 写入（或直接发 MQTT，由 EMQX 规则桥接到 TDengine）。

#### 3.9 切换读路径

`TelemetryController.getHistory()` — `telemetryService.page()` → `telemetryDao.query()`

`VisionEventController.page()` / `byDevice()` — `visionEventService.page()` → `visionEventDao.query()`

`VoiceEventController.page()` / `byDevice()` — 同上

`HealthScoreTask.calcCommunicationScore()` — `telemetryMapper.selectList()` → `telemetryDao.query24h()`

`EdgeNodeSimulator` — 最新遥测 `telemetryMapper.selectOne()` → `telemetryDao.latest()`

#### 3.10 MySQL Service 保留策略

`TelemetryServiceImpl`、`VisionEventServiceImpl`、`VoiceEventServiceImpl` 保留不删，但注入的 Mapper 不再被 Controller 或 Task 调用。作为降级回退路径：如果 TDengine 连接失败，Controller 可以回退到 MySQL Service。

### 步骤 4：降级容错

```java
// TelemetryController 伪代码
try {
    return tdengineTemplate.page(sql, page, size);
} catch (DataAccessException e) {
    log.warn("TDengine 不可用，降级到 MySQL: {}", e.getMessage());
    return telemetryService.page(page, wrapper); // 回退
}
```

## 测试验证

### 编译验证

```bash
cd D:/java/SmartLightingExp
mvn test
```

### 功能验证

1. 启动 TDengine + EMQX + 后端
2. 使用 MQTT 客户端模拟设备发送遥测到 `streetlight/SL-001/telemetry`
3. 在 TDengine CLI 中验证数据写入：
   ```sql
   SELECT * FROM smart_lighting.telemetry WHERE device_id = 'SL-001' LIMIT 5;
   ```
4. 调用 `GET /api/telemetry/history` 验证查询结果
5. 调用 `GET /api/vision-events/page` 验证事件查询
6. 调用 `GET /api/voice-events/page` 验证事件查询
7. 查看 Dashboard，验证遥测趋势图正常渲染
8. 查看设备详情，验证最新遥测显示正常
9. 等待一次健康评分计算，验证 `HealthScoreTask` 正常工作

### 回滚方案

如果出现问题，只需要：
1. 恢复 `MqttSubscriber` 中的 `telemetryMapper.insert()` 等写入代码
2. 恢复 Controller 和 Task 中的 MySQL 查询
3. 重启后端即可

数据层面：TDengine 和 MySQL 同时运行几天，通过对比两个库的数据量来确认一致性。确认一致后再在 EMQX 侧关闭"MySQL 写规则"（如果有的话），仅保留 TDengine 规则。

## 资源评估

### 服务器负载（2核2GB）

| 服务 | 内存占用 | CPU 占用 |
|------|---------|---------|
| MySQL 8.4 | ~400 MB | ~5% |
| EMQX | ~300 MB | ~5% |
| Spring Boot | ~500 MB | ~15% |
| **TDengine（新增）** | **~250 MB** | **~5%** |
| 系统 | ~300 MB | - |
| **合计** | **~1750 MB** | **~30%** |

> TDengine 参数优化（写入 `taos.cfg` 或在 `application.yaml` 的连接 URL 中配置）：
> ```
> numOfVnodeFetchThreads 1
> cacheSize 256
> ```
> 可进一步控制内存在 200 MB 以内。

## 实施优先级

| 步骤 | 内容 | 依赖 |
|------|------|------|
| 1 | Docker 部署 TDengine + 创建超级表 | 无 |
| 2 | EMQX 规则引擎配置（数据直写） | 步骤 1 |
| 3 | 后端添加 TDengine 依赖和配置 | 步骤 1 |
| 4 | 新建 TdengineTemplate + 三个 DAO | 步骤 3 |
| 5 | MqttSubscriber 删除 MySQL 写 | 步骤 2（EMQX 已承担写入） |
| 6 | Controller/Task 读路径切换 | 步骤 4 |
| 7 | 编译测试 + 端到端验证 | 步骤 5、6 |

步骤 1-2 可以独立做（EMQX 配置），不影响后端运行。步骤 3-7 在后端侧完成。
