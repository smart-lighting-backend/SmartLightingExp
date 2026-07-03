# MaxKB 接入说明

本项目通过 MaxKB 的 OpenAI 兼容对话接口提供维护知识库问答。

## 1. 导入知识库

1. 在 MaxKB 中创建知识库。
2. 上传 `docs/路灯常见故障手册.md`。
3. 创建应用并关联该知识库。
4. 在应用中生成 API Key。

## 2. 配置后端环境变量

```powershell
$env:MAXKB_CHAT_COMPLETIONS_URL="http://你的MaxKB地址/api/application/应用ID/chat/completions"
$env:MAXKB_API_KEY="你的API Key"
```

`application.yaml` 已预留：

```yaml
maxkb:
  chat-completions-url: ${MAXKB_CHAT_COMPLETIONS_URL:}
  api-key: ${MAXKB_API_KEY:}
  model: ${MAXKB_MODEL:gpt-3.5-turbo}
```

## 3. 调用后端问答接口

```http
POST /api/assistant/chat
Authorization: Bearer <系统登录token>
Content-Type: application/json

{
  "message": "灯不亮怎么办"
}
```

返回：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "type": "KNOWLEDGE_QA",
    "content": "..."
  }
}
```

## 4. 智能体阈值控制

同一个接口会先识别阈值控制意图。命中后直接修改后端真实策略，不调用 MaxKB。

```http
POST /api/assistant/chat
Authorization: Bearer <系统登录token>
Content-Type: application/json

{
  "message": "把阈值调到30"
}
```

返回：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "type": "THRESHOLD_UPDATED",
    "content": "已将光照触发阈值调整为 30 lux。",
    "action": {
      "name": "SET_LUX_LT_THRESHOLD",
      "luxLt": 30,
      "policyId": 1,
      "policyName": "光照低于阈值自动开灯"
    }
  }
}
```
