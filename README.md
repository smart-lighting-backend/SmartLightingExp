# 🏙️ SmartLightingExp — 智慧路灯 IoT 管理平台（后端）

面向市政道路照明的物联网管理系统后端服务，基于重庆交通大学 × 中软国际人才培养实训方案构建。通过传感器采集环境数据、MQTT 远程通信、后端自动策略控制，实现路灯的智能开关、调光、告警与能耗分析。

配套前端：[smart_lighting_vue](https://github.com/smart-lighting-backend/smart_lighting_vue)（Web）· 移动端：[SmartLightingAndroid](https://github.com/smart-lighting-backend/SmartLightingAndroid)（Kotlin Compose）

> 🎬 **演示视频**：https://b23.tv/BV1bJub6sEBi

## 🚀 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 17 |
| 框架 | Spring Boot | 3.4.3 |
| ORM | MyBatis-Plus | 3.5.9 |
| 数据库 | MySQL（阿里云 RDS） | 8.4 |
| 时序数据库 | TDengine | 3.3.2 |
| 消息协议 | MQTT（EMQX） | 6.2.1 |
| 认证授权 | JWT（jjwt 0.12.6）+ RBAC | — |
| 密码加密 | BCrypt + AES-256-CBC | — |
| API 文档 | Knife4j（OpenAPI 3） | 4.5.0 |
| 本地缓存 | Caffeine | — |
| Excel | Apache POI | 5.4.0 |
| 构建 | Maven | — |

## ✨ 核心功能

| 模块 | 说明 |
|------|------|
| 设备管理 | 路灯台账、区域分组、批量导入导出、高德地图定位、健康评分 |
| 遥测数据 | 光照/温湿度/PM2.5/AQI/PIR/车流量采集，写入 TDengine 时序库 |
| 设备控制 | MQTT 下发开/关/调光指令，手动 + 策略双模式，指令历史可追溯 |
| 告警管理 | 离线 / 故障 / 健康分过低三类，自动产生与恢复 |
| 照明策略 | 阈值/时间/场景规则引擎（条件 JSON、优先级、模拟测试） |
| 双层决策引擎 | 云端 DecisionEngine + 边缘 ConditionEvaluator（纯函数可迁移硬件），断网本地自主决策 |
| 设备 MQTT 鉴权 | EMQX 内置认证，每设备独立凭证（BCrypt + AES-256-CBC），ACL Topic 隔离 + SSL |
| AI 智能运维 | MaxKB RAG 维修诊断（本地正则 → AI 意图 → RAG 三段式），自然语言调参 |
| 能耗统计 | 日粒度节能率 / 用电量 / 碳减排估算 |
| 权限体系 | RBAC（4 角色 × 17 权限码），16 Controller 全面 `@RequirePermission` |
| 审计日志 | 关键操作自动记录 operator + IP + 时间 |

## 🏗️ 项目结构

```
src/main/java/com/experiment/smartlightingexp/
├── common/       # 统一响应 Result、全局异常、SecurityContext、@RequirePermission
├── config/       # JwtInterceptor、MqttConfig、MyBatisPlusConfig、CorsConfig、Knife4jConfig...
├── controller/   # 18 个控制器（Auth/Device/Alarm/Policy/Telemetry/Control/Dashboard/Assistant...）
├── dto/          # 请求/响应对象
├── engine/       # 决策引擎（DecisionEngine 云端 + ConditionEvaluator 边缘）
├── entity/       # 16 张表实体映射
├── mapper/       # MyBatis-Plus Mapper（纯注解，无 XML）
├── mqtt/         # MQTT 发布/订阅组件
├── service/      # 业务接口 + 15 个实现类
├── task/         # 定时任务（心跳检测、能耗统计、健康评分、边缘模拟、凭证初始化）
└── util/         # AesUtil、JwtUtil、EventTextNormalizer、SensorValidator
```

## 🛠️ 快速开始

### 环境要求

- JDK 17
- Maven 3.9+
- MySQL 8.x（或阿里云 RDS）
- TDengine 3.x（可选，遥测数据）
- EMQX（可选，MQTT 设备接入）
- MaxKB（可选，AI 维修诊断）

### 配置与运行

```bash
# 1. 配置数据库（环境变量注入，避免敏感信息入库）
set DB_PASSWORD=your_password

# 2. 配置 MaxKB（可选）
set MAXKB_CHAT_COMPLETIONS_URL=http://your-maxkb/api/application/{appId}/chat/completions
set MAXKB_API_KEY=your_key

# 3. 启动
./mvnw spring-boot:run
```

### 数据库初始化

`database/` 目录包含完整建表与种子数据脚本，按编号顺序执行（`01_init_schema.sql` → `02_seed_data.sql` → ...）。

## 🔑 关键设计

### 设备 MQTT 鉴权（安全可信控制 IR-11）

1. 每台设备独立 MQTT 凭据：username = deviceId，密码 = 出厂编号 + 识别码
2. 密码双重加密存储：BCrypt 哈希（验证）+ AES-256-CBC 可逆加密（运维可查原始密码）
3. EMQX 认证链 + ACL Topic 隔离（设备仅能访问自己的 topic）+ SSL/TLS 传输加密
4. 设备增删改自动同步 EMQX；启动时 `DeviceCredentialInitializer` 自动为存量设备补发凭证

### 边缘决策（断网可用）

- 云端 `DecisionEngine` 评估遥测 → 下发控制
- 边缘 `ConditionEvaluator` 纯函数实现（与云端逻辑一致，可迁移到硬件）
- 设备断网时本地自主判断开关/调光，恢复后决策日志自动同步

### 健康评分

每日凌晨 2:00 从四个维度打分（0-100）：离线频次 30% + 通信质量 25% + 指令响应率 25% + 传感器异常率 20%，低于 60 自动产生 HEALTH_LOW 告警。

## 📚 文档

| 文档 | 说明 |
|------|------|
| `docs/项目上下文.md` | 项目完整背景、架构、数据库、业务流程 |
| `docs/API接口文档.md` | 后端接口总览 |
| `docs/移动端接口文档.md` | 33 个移动端接口（请求/响应/分页） |
| `docs/真实硬件设备实现文档.md` | 小熊派 BearPi 接入说明 |
| `docs/创新点实现逻辑总结.md` | 核心创新点技术细节 |

## 📄 许可证

MIT License
