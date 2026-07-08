# TDengine + EMQX 规则引擎配置问题记录

> 日期：2026-07-07 | 场景：在阿里云 ECS 2核2GB 上 Docker 部署 TDengine，通过 EMQX 规则引擎将 MQTT 消息桥接到时序数据库

---

## 问题 1：taosd 未启动，CLI 连接报 `Conn is broken`

**现象**：

```bash
docker exec -it tdengine taos
# failed to connect to server, reason: Conn is broken
```

`docker exec tdengine ps aux` 只看到 `taosadapter` 和 `taos-explorer`，缺了核心进程 `taosd`。

**原因**：启动容器时用了 `TAOS_FQDN=172.17.0.1`（默认 bridge 的网关），但容器后来接入了自定义网络 `iot-net`，导致 `taosd` 绑定地址解析异常，进程起不来。

**解决**：删除容器重建，`TAOS_FQDN` 必须与容器 hostname 一致，且容器需在启动时就指定目标网络：

```bash
docker run -d --name tdengine \
  --restart always \
  --network iot-net \
  --hostname tdengine \
  -p 6030:6030 -p 6041:6041 \
  -v /data/tdengine:/var/lib/taos \
  -e TAOS_FQDN=tdengine \
  tdengine/tdengine:3.3.2.0
```

**教训**：`TAOS_FQDN` 决定了 `taosd` 绑定的网络地址，跨网络时必须用容器名而非 IP，且容器名和 hostname 要一致。不要先启动再 `docker network connect`。

---

## 问题 2：动作全部通过但 TDengine 无数据（子表名含连字符）

**现象**：EMQX 规则命中 24 次，动作执行全部"通过"（失败 0），但 `SELECT COUNT(*) FROM telemetry` 返回 0。

**原因**：EMQX 的"动作通过"只表示请求发送到 TDengine 了，TDengine 静默拒绝不合法的 SQL。设备 ID `SL-001` 生成的子表名 `t_SL-001` 含有 `-`，TDengine 表名只支持字母、数字、下划线。

**解决**：EMQX SQL 模板里用 `replace(dev_id, '-', '_')` 生成合法子表名，TAG 值 `'SL-001'` 保留连字符不受影响。

```sql
-- 错误
INSERT INTO t_SL-001 USING telemetry TAGS ('SL-001') VALUES (...)

-- 正确
INSERT INTO t_SL_001 USING telemetry TAGS ('SL-001') VALUES (...)
```

**教训**：TDengine 的动作"通过"不代表真正写入成功，需要直接查表验证。设备 ID 规范最好从一开始就约束为 `[a-zA-Z0-9_]`。

---

## 问题 3：device_id 始终为 null（TAG 名冲突 + substring 函数不可靠）

**现象**：规则命中、动作通过，但 `device_id` 列全是 `null`。

**排查过程**：

1. 先用 `substring(topic, 13, -10)` 从 topic 提取设备 ID → null
2. 改用 `split(topic, '/')` + `nth(2, ...)` → 还是 null（EMQX 5.x SQL 模板不支持这些函数）
3. 直接从 MQTT payload 取 `payload.deviceId as device_id` → 仍然 null

**最终发现**：SELECT 别名 `device_id` 与 TDengine 超级表的 TAG 名 `device_id` **同名**，EMQX 在绑定变量时产生冲突，传给 TDengine 的是 null。

**解决**：SELECT 中把别名改成 `dev_id`（不与任何 TAG 名冲突）：

```sql
-- 错误
SELECT payload.deviceId as device_id FROM "streetlight/+/telemetry"

-- 正确
SELECT payload.deviceId as dev_id FROM "streetlight/+/telemetry"
```

动作 SQL 模板对应改为 `${dev_id}`。

**教训**：SELECT 输出的字段别名不能和 TDengine 超级表的 TAGS 列名重名。`substring`/`nth` 等函数在 EMQX 5.x 的动作 SQL 模板中不可靠，优先用 `payload.xxx` 直接从消息体取值。

---

## 问题 4：EMQX 5.x 编辑规则后不生效

**现象**：在 Dashboard 里修改规则的 SQL 或动作模板后，新数据仍然按旧逻辑执行。

**原因**：EMQX 5.x 某些版本的规则编辑存在缓存问题，编辑保存后不会立即刷新动作的字段绑定。

**解决**：删除整条规则，从头重建。不要只编辑。

**教训**：EMQX 5.x 做规则变更时，如果改了几次都不见效，直接删了重建最省时间。

---

## 问题 5：时间戳 `${ts}` 没加引号

**现象**：TDengine INSERT 语法错误（静默失败）。

**原因**：模拟器发的是字符串时间戳 `"2026-07-07T16:30:00"`，TDengine 需要 `'${ts}'`（带单引号）才能把字符串转为 TIMESTAMP。

```sql
-- 错误
VALUES (${ts}, ...)

-- 正确
VALUES ('${ts}', ...)
```

同样，所有字符串字段（`${type}`、`${content}`、`${event_type}` 等）都必须加单引号。

---

## 问题 6：EMQX 容器无常用命令，排查不便

**现象**：`docker exec emqx ping tdengine` 返回 `exec: "ping": executable file not found`。

EMQX 容器是最精简镜像，没有 `ping`、`nslookup` 等命令。

**替代方案**：

```bash
# 测试 DNS 解析
docker exec emqx getent hosts tdengine

# 查看规则列表
docker exec emqx emqx ctl rules list

# 查看规则详情
docker exec emqx emqx ctl rules show telemetry_bridge

# 查看客户端连接
docker exec emqx emqx ctl clients list
```

---

## 问题 7：启动报错 `no taos in java.library.path`（JNI vs REST 驱动）

**现象**：Spring Boot 启动时报：

```
UnsatisfiedLinkError: no taos in java.library.path
```

**原因**：TDengine JDBC 驱动提供了两种连接方式：

| | Native (`jdbc:TAOS://`) | REST (`jdbc:TAOS-RS://`) |
|---|---|---|
| 实现方式 | JNI 调用 C 客户端 `libtaos.so` / `taos.dll` | HTTP 请求 taosAdapter（6041 端口） |
| 是否需要本地库 | 必须安装 TDengine 客户端 | 不需要，纯 Java |
| 延迟 | 更低（二进制协议） | 略高（HTTP 开销） |
| 适用场景 | 高频写入、大批量查询 | 普通读写、无客户端环境 |

项目用的是 `jdbc:TAOS://`（Native），而本地 Windows 开发机没装 TDengine 客户端，没有 `taos.dll`，所以 JNI 加载失败。

**解决**：改用 REST 连接，纯 Java 无需本地库：

```yaml
# 错误（需要本地 C 库）
tdengine:
  url: jdbc:TAOS://47.96.27.141:6030/smart_lighting

# 正确（纯 Java，无需本地库）
tdengine:
  url: jdbc:TAOS-RS://47.96.27.141:6041/smart_lighting
```

端口也要从 `6030`（Native）改为 `6041`（REST / taosAdapter）。

**什么时候用 Native？** 当后端和 TDengine 部署在同一台机器上，且写入/查询量极大（每秒上千次）时，Native 的二进制协议比 HTTP 更高效。对于本项目（后端只做读，EMQX 扛写，查询量小），REST 完全够用，延迟差异感知不到。

**教训**：远程连接用 REST（`TAOS-RS`），本机直连才考虑 Native（`TAOS`）。开发/测试阶段一律用 REST，避免各平台装客户端库的麻烦。

### TDengine 端
- `TAOS_FQDN` = 容器名 = hostname，在 `docker run` 时指定
- 数据库创建时指定 `KEEP 365` 自动过期老数据
- 子表名只用 `[a-zA-Z0-9_]`，用 `replace()` 清洗

### EMQX 端
- SELECT 输出别名不要和超级表 TAG 名重名（用 `dev_id` 而非 `device_id`）
- 时间戳和字符串字段在 SQL 模板里都加单引号
- 「未定义变量作为 NULL」开关打开
- 改动不生效就删了重建
- `payload.xxx` 取值比 topic 解析可靠
