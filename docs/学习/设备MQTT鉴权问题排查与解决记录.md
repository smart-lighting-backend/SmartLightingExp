# 设备 MQTT 鉴权问题排查与解决记录

> 日期：2026-07-11 | 场景：为智慧路灯项目实现每设备独立 MQTT 鉴权 + SSL 传输加密 + ACL Topic 隔离

---

## 问题总览

| # | 问题 | 严重程度 | 类型 |
|---|------|----------|------|
| 1 | SSL 端口 8883 未在安全组开放 → 后端无法连接 | 致命 | 服务器配置 |
| 2 | Docker 容器未暴露 8883/8084 端口 → 安全组开了也连不上 | 致命 | 服务器配置 |
| 3 | 重建 EMQX 容器后挂载空目录 → 配置文件丢失 | 致命 | 操作失误 |
| 4 | EMQX 重建后所有配置丢失（认证器、ACL、规则引擎） | 高 | 操作影响 |
| 5 | Java SSL hostname 验证失败（自签名证书无 IP SAN） | 高 | 代码 |
| 6 | `device_credential` 表结构不一致（旧表残留） | 中 | 数据库 |
| 7 | EMQX MySQL 认证器 Prepared Statement + caching_sha2_password 不兼容 | 致命 | EMQX Bug |
| 8 | `disable_prepared_statements` 设置不生效 | 致命 | EMQX Bug |
| 9 | 前端 KeepAlive 缓存导致返回列表白屏 | 中 | 前端 |
| 10 | system/alarms 发布超时（单线程阻塞） | 中 | 代码 |

---

## 问题 1：SSL 端口未开放

**现象**：后端启动报 `Connection refused: getsockopt` 连接 `ssl://47.96.27.141:8883`。

**原因**：阿里云安全组只开放了 TCP 1883，SSL 8883 和 WSS 8084 未开放。

**解决**：在安全组入方向添加两条规则：
- 自定义 TCP `8883` → MQTT SSL 设备连接
- 自定义 TCP `8084` → WebSocket WSS 前端连接

**教训**：SSL/WSS 本质上就是 TCP 端口上的加密流量，安全组只需放行 TCP 端口即可。

---

## 问题 2：Docker 容器端口映射缺失

**现象**：安全组开了 8883，但后端仍然 `Connection refused`。

**原因**：原 EMQX 容器创建时只映射了 1883/8083/18083，没有 8883/8084。流量到安全组后被 Docker 丢弃。

**解决**：删除容器重建，增加端口映射：
```bash
docker rm -f emqx
docker run -d --name emqx --restart always --network iot-net \
  -p 1883:1883 -p 8883:8883 -p 8083:8083 -p 8084:8084 -p 18083:18083 \
  emqx/emqx:6.2.1
```

**教训**：安全组 + Docker 端口映射两层都要检查，缺一不可。

---

## 问题 3：重建容器时挂载空目录导致配置丢失

**现象**：重建 EMQX 后容器不断重启，日志显示 `emqx.conf is not found`。

**原因**：`docker run` 时加了 `-v /opt/smart-lighting/mqtt/emqx/etc:/opt/emqx/etc`，但宿主机目录是空的，覆盖了容器内默认配置。

**解决**：强制删除容器，重建时**不挂载** etc/data 目录，让 EMQX 自动初始化：
```bash
docker rm -f emqx
docker run -d --name emqx --restart always --network iot-net \
  -p 1883:1883 -p 8883:8883 -p 8083:8083 -p 8084:8084 -p 18083:18083 \
  emqx/emqx:6.2.1
```

**教训**：永远不要用空目录挂载覆盖容器内的配置文件目录。如需持久化，先 `docker cp` 出默认配置再挂载。

---

## 问题 4：EMQX 重建后配置全丢

**现象**：重建后认证器列表为空、ACL 恢复默认、规则引擎无规则、TDengine 桥接全部丢失。

**原因**：EMQX 容器删除时，所有运行态配置（认证器、ACL、规则、连接器）随容器一起销毁。

**解决**：通过 EMQX REST API 逐一重建：
1. 管理员密码 → `PUT /api/v5/users/admin`
2. 内置数据库认证器 → `POST /api/v5/authentication` (built_in_database)
3. 服务账号 backend → `POST /api/v5/authentication/.../users`
4. 设备用户 20 个 → 逐个 POST
5. MySQL 认证器 → `POST /api/v5/authentication` (mysql, bcrypt)
6. ACL 规则 → `PUT /api/v5/authorization/sources/file`
7. TDengine 连接器 → `POST /api/v5/connectors` (tdengine)
8. 三条规则引擎 → `POST /api/v5/rules` + `POST /api/v5/actions`

**教训**：EMQX 的配置（认证器、ACL、规则引擎）需要通过 API 或配置文件持久化。建议将配置写成脚本或使用 EMQX 的配置备份功能。

---

## 问题 5：Java SSL Hostname 验证失败

**现象**：`SSLHandshakeException: No subject alternative names matching IP address 47.96.27.141 found`

**原因**：EMQX 自签名证书中没有 IP SAN（Subject Alternative Name）。Java SSL 在 TLS 握手后验证主机名是否匹配证书 SAN，trustAll 只能绕过"信任"检查，不能绕过"主机名"检查。

**解决**：在 MqttConnectOptions 上关闭 hostname 验证：
```java
options.setHttpsHostnameVerificationEnabled(false);
```

> MockDataGenerator 和 MqttConfig 两处都需要加。

**教训**：Java 的 SSL 验证分两层：① 证书信任（TrustManager）② 主机名匹配（HostnameVerifier）。自签名证书需要同时处理两层。

---

## 问题 6：device_credential 表结构不一致

**现象**：`Unknown column 'username' in 'field list'`

**原因**：RDS 上已存在同名表，是之前某次测试留下的旧结构（字段为 `device_secret`, `device_token` 等），与代码中 DeviceCredential 实体不匹配。

**解决**：DROP 旧表，CREATE 新表：
```sql
DROP TABLE IF EXISTS device_credential;
CREATE TABLE device_credential (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id  VARCHAR(50) NOT NULL,
    username   VARCHAR(50) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    factory_serial_encrypted VARCHAR(255) NOT NULL,
    device_id_code_encrypted VARCHAR(255) NOT NULL,
    ...
);
```

**教训**：数据库迁移脚本应检查已有表结构，不能仅依赖 `IF NOT EXISTS`。必要时用 `DROP TABLE IF EXISTS` + `CREATE TABLE` 覆盖旧结构。

---

## 问题 7 & 8：EMQX MySQL 认证器与 RDS MySQL 8.4 不兼容（核心难题）

**现象**：设备 MQTT 连接报 `无权连接`，EMQX 日志反复出现：
```
mysql_auth_connector_query_exception
reason: function_clause, mysql_protocol, execute
State: caching_sha2_password
```

**排查过程**：
1. DNS 解析正常（`getent hosts` 成功）
2. TCP 连通正常（`echo >/dev/tcp/.../3306` 成功）
3. MySQL 用户能正常登录（后端 JDBC 连接 RDS 正常）
4. 改用 IP 替代域名 → 无效
5. `disable_prepared_statements: true` → 无效（EMQX 6.2.1 bug，该设置不生效）

**根因**：EMQX 6.2.1 的 Erlang MySQL 驱动（`mysql_protocol.erl`）在使用 Prepared Statement 方式与 MySQL 8.4 的 `caching_sha2_password` 认证插件交互时，协议级 `function_clause` 异常。这是 EMQX 已知的版本兼容性问题。

**最终方案**：放弃 MySQL 认证器，改用 EMQX 内置数据库（built_in_database）存储设备密码。同时准备好 HTTP Auth 备用方案（`MqttAuthController`），后端部署到服务器上即可激活。

**教训**：EMQX 6.2.1 + MySQL 8.4 组合存在认证驱动兼容性问题。生产环境建议：
- 升级 EMQX 到 6.3+（可能修复了该 bug）
- 或使用 EMQX HTTP Auth 回调自定义后端
- 或将 MySQL 用户认证插件改为 `mysql_native_password`

---

## 问题 9：前端 KeepAlive 导致返回白屏

**现象**：从设备详情页点击返回，设备管理页白屏。

**原因**：Devices.vue 被 `<KeepAlive>` 缓存，从详情返回时组件从缓存恢复，但 `onMounted` 不会重新触发，数据未重新加载。

**解决**：添加 `onActivated` 钩子，每次从缓存恢复时重新加载数据：
```javascript
import { onActivated } from 'vue'
onActivated(() => {
  loadDevices()
})
```

**教训**：使用 KeepAlive 的页面需要同时处理 `onMounted`（首次）和 `onActivated`（缓存恢复）两个生命周期。

---

## 问题 10：MqttPublisher 单线程阻塞

**现象**：`MQTT publish timeout (10s), topic=system/alarms`

**原因**：MqttPublisher 使用 `SingleThreadExecutor`，多个告警事件同时发布时排队阻塞，后续发布全部超时。

**解决**：改为 `CachedThreadPool`，超时降为 5s，失败不抛异常只打 warn：
```java
private final ExecutorService publishExecutor = Executors.newCachedThreadPool();
```

---

## 经验总结

1. **安全组 + Docker 端口双重检查**：端口不通时不要只查安全组，也要查 `docker ps` 看端口映射
2. **不要用空目录挂载覆盖容器配置**：先 `docker cp` 备份，再挂载
3. **EMQX 配置需脚本化管理**：认证器、ACL、规则引擎等都需通过 API 重建，建议写成脚本
4. **Java SSL 双层验证**：trustAll + disableHostnameVerification 都要处理
5. **EMQX 6.2.1 + MySQL 8.4 不兼容**：用 built_in 或 HTTP Auth 替代
6. **KeepAlive 页面需要 onActivated**：被缓存的组件不会重新执行 onMounted
7. **发布线程不要用单线程**：CachedThreadPool 避免排队阻塞
