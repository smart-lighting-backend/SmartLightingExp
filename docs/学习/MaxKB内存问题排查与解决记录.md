# MaxKB 内存问题排查与解决记录

> 记录日期：2026-07-08

## 一、问题现象

后端日志反复出现 MaxKB 请求失败：

```
ERROR MaxKbClient - MaxKB 请求失败: I/O error on POST request for
"http://47.96.27.141:8080/chat/api/.../chat/completions":
HTTP/1.1 header parser received no bytes
```

移动端 AI 助手间歇性不可用，且 MaxKB 容器不定期自动重启。

## 二、根因分析

### 2.1 服务器资源

| 指标 | 实际值 | 说明 |
|------|--------|------|
| 总内存 | 3.48 GB | 阿里云 ECS 配置 |
| Swap | 2 GB | 已用 413MB |
| 已运行容器 | EMQX + TDengine + MaxKB | 3 个容器 |

### 2.2 排查过程

**第一层：确认容器状态**

```bash
docker ps -a | grep maxkb
```

容器频繁重启（`Up About a minute`）。

**第二层：检查是否被系统 OOM 杀掉**

```bash
dmesg | grep -i "out of memory\|oom" | tail -10
```

输出：
```
Out of memory: Killed process 2571327 (celery) total-vm:1617744kB, anon-rss:614376kB
Out of memory: Killed process 2572180 (celery) total-vm:1390556kB, anon-rss:608068kB
```

**根因确认：celery 进程每次占用 ~600MB 内存，触发系统 OOM Killer。**

**第三层：MaxKB 内存占用分析**

```bash
docker stats maxkb --no-stream
```

MaxKB 内部运行 4 个服务：

| 服务 | 作用 | 内存占用 | 聊天是否需要 |
|------|------|---------|:---:|
| PostgreSQL | 数据库 | ~50MB | ✅ |
| Redis | 缓存 | ~10MB | ✅ |
| Gunicorn | HTTP + 聊天请求 | ~200MB | ✅ |
| Celery | 文档向量化 | **~600MB** | ❌ 不需要 |

**第四层：celery 被杀 → 触发连锁重启**

`start-all.sh` 使用 `wait -n` 监听所有子进程。celery 被 OOM 杀后，脚本触发 `System is shutting down`，杀掉 PG/Redis/Gunicorn，整个 MaxKB 重启。重启后 celery 又启动 → 又被 OOM 杀 → 死循环。

**第五层：简单问题能过，复杂 RAG 问题不过**

因为 Gunicorn worker 配置 `--timeout 30`，复杂问题知识库检索耗时 >30s 时被 Gunicorn 杀掉。

### 2.3 问题链路图

```
celery 启动 → 占用 600MB → 系统内存不足 → OOM Killer 杀 celery
  ↓
start-all.sh 检测子进程死亡 → System is shutting down
  ↓
杀掉 PG/Redis/Gunicorn → 容器重启 → celery 又启动 → 死循环
```

## 三、解决方案

### 3.1 限制 MaxKB 容器内存上限（兜底）

```bash
docker update --memory 1.8g --memory-swap 2g maxkb
```

### 3.2 删除大 PDF 文档（减少向量化压力）

登录 MaxKB 管理后台（`http://47.96.27.141:8080`），删除 3 个最大的 PDF 知识库文档。

### 3.3 用假 celery 替身骗过启动脚本（核心修复）

Celery 只负责文档向量化，删除 PDF 后不再需要。直接删除 celery 会导致启动脚本因 `Permission denied` 卡住。

**方案**：用空 shell 脚本替换 celery 二进制，骗过 `start-all.sh`：

```bash
docker exec maxkb sh -c "cat > /opt/py3/bin/celery << 'SCRIPT'
#!/bin/sh
while true; do sleep 3600; done
SCRIPT
chmod +x /opt/py3/bin/celery"

docker restart maxkb
```

假 celery 只占几 KB 内存，不处理任何工作，但能让启动脚本不报错。

### 3.4 Java 后端超时调整

```java
// MaxKbClient.java
HttpClient httpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(15))  // 5s → 15s
    .build();
JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
factory.setReadTimeout(Duration.ofSeconds(120));  // 60s → 120s
```

## 四、修复后状态

| 指标 | 修复前 | 修复后 |
|------|--------|--------|
| MaxKB 内存 | 1.27 GB | ~400 MB |
| Celery | 600MB（反复被杀） | 假替身（~0MB） |
| 容器稳定性 | 每 3-5 分钟重启 | 稳定运行 |
| AI 简单对话 | 间歇性失败 | ✅ 正常 |
| AI 复杂 RAG | 必崩 | ✅ 正常 |

## 五、后续注意事项

1. **每执行 `docker restart maxkb` 后**，假 celery 替身仍然有效（已写入容器文件系统），无需重新设置。
2. **如果重建 MaxKB 容器**（`docker rm` + `docker run`），需要重新执行方案 3.3。
3. **如果需要恢复 celery**（后续需向量化新文档）：
   ```bash
   docker exec maxkb sh -c "cat > /opt/py3/bin/celery << 'SCRIPT'
   #!/bin/sh
   /opt/py3/bin/python /opt/py3/bin/celery_original \$(echo \"\$@\" | sed 's/-P solo/-P threads/') 2>/dev/null || echo celery_disabled
   SCRIPT"
   ```
   恢复后需重启容器，向量化完成后可再次替换回假替身。
4. **如偶发超时**：Gunicorn worker 30s 超时是 MaxKB 容器内置的，复杂 RAG 查询可能边界触发。如频繁出现，可考虑 ECS 升配到 8GB。
