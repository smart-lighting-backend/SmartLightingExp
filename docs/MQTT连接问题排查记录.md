# MQTT 连接问题排查记录

> 日期：2026-07-02
> 现象：应用启动后 MQTT 连接成功，2 秒后被服务器主动断开（日志显示"已断开连接"），自动重连反复失败。

---

## 问题现象

```
MQTT connected to tcp://47.96.27.141:1883         ← 连接成功
MQTT subscriber ready, topic=streetlight/+/telemetry  ← 订阅成功
MQTT connection lost: 已断开连接                  ← 2 秒后断开
MQTT connection lost: 已断开连接                  ← 自动重连后再次断开
```

## 排查过程

### 第 1 步：确认 EMQX 服务是否正常运行

```bash
# 检查 MQTT 端口
curl http://47.96.27.141:18083/api/v5/status
# → Node emqx@172.17.0.2 is started
# → emqx is running
```

结果：EMQX 服务正常。

### 第 2 步：确认认证用户和密码

```bash
# 查看 EMQX 认证方式
docker exec emqx cat /opt/emqx/data/configs/cluster.hocon | grep -A 10 authentication
# → mechanism = password_based, backend = built_in_database, name = plain

# 查看内置数据库中的用户列表
curl -s "http://47.96.27.141:18083/api/v5/authentication/password_based:built_in_database/users"
# → [{"user_id": "backend"}]
```

结果：`backend` 用户存在，密码为 `123456`（plain 方式存储）。

### 第 3 步：对比测试 Python 客户端连接

```bash
# 用 Python Paho 客户端测试连接
# 测试 1：使用不同 client ID → 连接正常，稳定保持
# 测试 2：使用相同 client ID "smart-lighting-backend" → 连接后被断开
```

**结论根因：** `smart-lighting-backend` 这个 client ID 在反复重连中被 EMQX 加入了限制机制，导致该 ID 被服务器拒绝。

### 修复

将 `application.yaml` 中的 `client-id` 改为新名称：

```yaml
mqtt:
  client-id: smart-lighting-backend-v2
```

重启后连接恢复正常。

## 经验总结

| 要点 | 说明 |
|------|------|
| client-id 必须唯一 | MQTT 协议要求每个客户端有唯一 ID，重复连接会被服务器拒绝或踢下线 |
| 避免反复重连 | 短时间内频繁连接/断开同一 client-id 可能触发 EMQX 的反抖/限流机制 |
| client-id 变更成本低 | 改一行配置文件即可，不需要修改 EMQX 服务端配置 |
| 重启前先踢旧会话 | 如果旧 client-id 还在线，可通过 EMQX Dashboard 手动踢掉 |
