# TDengine 后端集成问题记录

> 日期：2026-07-08 | 场景：Spring Boot 项目接入 TDengine 时序数据库后端代码改造过程中遇到的问题

---

## 问题 1：JNI 驱动报 `no taos in java.library.path`

**现象**：Spring Boot 启动报错：

```
UnsatisfiedLinkError: no taos in java.library.path
```

**原因**：TDengine JDBC 驱动默认使用 JNI Native 连接（`jdbc:TAOS://`），需要本地安装 `libtaos.so` / `taos.dll`。Windows 开发机没有 TDengine 客户端。

**解决**：改用 REST 连接（`jdbc:TAOS-RS://`），纯 Java 实现，无需本地 C 库。端口也从 6030 改为 6041。

```yaml
# 错误（需要本地 C 库）
tdengine:
  url: jdbc:TAOS://47.96.27.141:6030/smart_lighting

# 正确（纯 Java，通用性好）
tdengine:
  url: jdbc:TAOS-RS://47.96.27.141:6041/smart_lighting
```

**教训**：远程连接用 REST（`TAOS-RS`），本机直连才考虑 Native。开发/测试阶段一律用 REST，避免各平台安装客户端库。

---

## 问题 2：MyBatis-Plus 将 SQL 路由到 TDengine 而非 MySQL

**现象**：启动后定时任务报错 `Table does not exist`，错误堆栈显示 SQL 被发到了 `com.taosdata.jdbc`（TDengine）而非 MySQL。

```
SELECT id,device_id,... FROM device WHERE deleted=0
→ TDengine ERROR: Table does not exist
```

**原因**：`TdengineConfig` 中将 `HikariDataSource` 注册为 Spring Bean（`@Bean public DataSource tdengineDataSource()`），导致 Spring 容器中有两个 DataSource。MyBatis-Plus 自动配置无法区分主从，选错了数据源。

**解决**：不将 TDengine DataSource 注册为 Spring Bean。在 `TdengineConfig` 中直接创建 JdbcTemplate，DataSource 作为局部变量不对外暴露。

```java
// 错误：暴露了两个 DataSource，MyBatis-Plus 选错
@Bean
public DataSource tdengineDataSource(TdengineProperties props) {
    return new HikariDataSource(config);  // 被 MyBatis-Plus 误选
}

// 正确：DataSource 不暴露为 Bean
@Bean
public JdbcTemplate tdengineJdbcTemplate(TdengineProperties props) {
    HikariDataSource ds = new HikariDataSource(config);  // 局部变量
    return new JdbcTemplate(ds);
}
```

**教训**：多数据源场景下，辅助数据源不要注册为 Spring Bean，直接在 `@Bean` 方法内部构造并传给 JdbcTemplate。如果需要暴露，必须加 `@Primary` 标注主数据源。

同时加 `config.setInitializationFailTimeout(-1)` 防止 HikariCP 启动时验证连接导致 Startup 卡死。

---

## 问题 3：EMQX 崩溃 — 系统文件描述符耗尽

**现象**：后端突然无法连接 MQTT，`Connection refused`。SSH 到服务器发现 EMQX 容器已停止，`docker start emqx` 报 `too many open files in system`。

**原因**：2核4GB 阿里云 ECS 默认 `file-max = 359,865`，四个 Docker 容器（EMQX + MaxKB + TDengine + 系统进程）累计打开约 359,776 个文件描述符，使用率 99.98%，EMQX 启动时无法分配新 fd 直接崩溃。

```
$ cat /proc/sys/fs/file-nr
359776   0   359865    ← 已用 / 总数，几乎满了
```

**解决**：

```bash
# 临时调大
echo 524288 > /proc/sys/fs/file-max

# 永久保存
echo "fs.file-max = 524288" >> /etc/sysctl.conf
```

调大后使用率从 99.98% 降到 68%，EMQX 恢复正常。

**教训**：Docker 多容器部署时文件描述符消耗叠加，小型 ECS 默认值可能不足。建议部署后检查 `file-nr`，提前调大 `file-max`。EMQX（Erlang/OTP）是 fd 消耗大户，每增加一个客户端连接 + topic 订阅都会打开 fd。

---

## 问题 4：taos-explorer WebSocket 报错

**现象**：浏览器访问 `http://47.96.27.141:6060`，登录页提示：

```
Internal error: WebSocket internal error: IO error:
failed to lookup address information: Name or service not known
```

**原因**：TDengine 默认配置文件 `/etc/taos/explorer.toml` 中 `cluster = "http://buildkitsandbox:6041"`，`buildkitsandbox` 是占位域名，浏览器无法解析。

**解决**：将占位域名替换为 `127.0.0.1`：

```bash
docker exec tdengine sed -i 's/buildkitsandbox/127.0.0.1/g' /etc/taos/explorer.toml
docker restart tdengine
```

**教训**：Docker 部署 TDengine 后需要检查并修改 explorer 配置文件中的默认地址，否则 Web 探勘器不可用。同类的还有 `x_api`、`grpc` 等字段，也需要按实际部署调整。

---

## 总结

本次后端集成共遇到 4 个问题，均已解决：

| # | 问题 | 根因 | 影响范围 |
|---|------|------|:---:|
| 1 | JNI 驱动加载失败 | Windows 无 taos.dll | 本地开发 |
| 2 | MyBatis-Plus 数据源选错 | 两个 DataSource Bean 冲突 | 全部 SQL |
| 3 | EMQX 崩溃 | 系统 fd 耗尽 | MQTT 连接 |
| 4 | Explorer WebSocket 报错 | 配置文件占位域名 | Web 管理 |
