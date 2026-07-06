# 设备管理 CRUD 接口开发日志

## 1. 开发目标

本次开发只在后端完成设备管理能力，不涉及前端页面改动。

目标是补齐设备台账管理的完整 CRUD 能力：

- 分页查询设备列表。
- 按主键查询设备详情，用于编辑回填。
- 新增设备。
- 修改设备。
- 逻辑删除设备。
- 支持常用筛选条件：关键字、区域、状态、启用状态。
- 对核心字段做参数校验和业务校验。

## 2. 涉及文件

| 文件 | 说明 |
|---|---|
| `controller/DeviceController.java` | 新增设备管理 CRUD 接口入口 |
| `service/DeviceService.java` | 新增设备管理业务方法声明 |
| `service/impl/DeviceServiceImpl.java` | 实现分页、新增、修改、逻辑删除和唯一校验 |
| `mapper/DeviceMapper.java` | 新增按 `device_id` 查询任意设备记录的方法，用于唯一性校验 |
| `dto/DevicePageRequest.java` | 设备分页查询请求参数 |
| `dto/DeviceUpsertRequest.java` | 设备新增/修改请求参数 |

## 3. 接口总览

接口统一挂载在后端 `/api` 前缀下。

| 功能 | 方法 | 接口 | 说明 |
|---|---|---|---|
| 分页查询 | GET | `/api/devices/page` | 查询未删除设备，支持分页和条件过滤 |
| 查询详情 | GET | `/api/devices/manage/{id}` | 按数据库主键查询设备，用于管理端编辑回填 |
| 新增设备 | POST | `/api/devices` | 新增一条设备台账 |
| 修改设备 | PUT | `/api/devices/{id}` | 按数据库主键修改设备台账 |
| 删除设备 | DELETE | `/api/devices/{id}` | 按数据库主键逻辑删除设备 |

## 4. 分页查询

### 4.1 接口

```http
GET /api/devices/page
```

### 4.2 请求参数

| 参数 | 类型 | 是否必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `pageNum` | Long | 否 | `1` | 当前页码，小于 1 时按 1 处理 |
| `pageSize` | Long | 否 | `10` | 每页条数，小于 1 时按 1 处理，最大限制为 100 |
| `keyword` | String | 否 | 无 | 关键字，匹配 `deviceId`、`name`、`location` |
| `area` | String | 否 | 无 | 区域过滤 |
| `status` | Integer | 否 | 无 | 设备状态：`0` 停用，`1` 在线，`2` 离线，`3` 异常 |
| `enabled` | Boolean | 否 | 无 | 是否启用 |

### 4.3 查询规则

分页查询只返回未逻辑删除的数据：

```text
deleted = false
```

排序规则：

```text
area ASC, deviceId ASC
```

关键字查询规则：

```text
deviceId LIKE keyword
OR name LIKE keyword
OR location LIKE keyword
```

### 4.4 请求示例

```http
GET /api/devices/page?pageNum=1&pageSize=10&keyword=SL&area=A区&status=1&enabled=true
```

### 4.5 响应示例

接口返回项目统一响应结构 `Result<Page<Device>>`。

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "records": [
      {
        "id": 1,
        "deviceId": "SL-001",
        "name": "南门-01",
        "area": "A区",
        "location": "106.5622,29.5621",
        "status": 1,
        "healthScore": 98.50,
        "topicPrefix": "streetlight",
        "lastHeartbeatAt": "2026-07-02T09:18:06",
        "latestData": null,
        "lastManualAt": null,
        "enabled": true,
        "deleted": false,
        "createTime": "2026-07-02T09:00:00",
        "updateTime": "2026-07-02T09:00:00"
      }
    ],
    "total": 1,
    "size": 10,
    "current": 1,
    "pages": 1
  }
}
```

## 5. 查询详情

### 5.1 接口

```http
GET /api/devices/manage/{id}
```

### 5.2 请求参数

| 参数 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| `id` | Long | 是 | 设备数据库主键 |

### 5.3 业务规则

- 如果设备不存在，返回 `404`。
- 如果设备已逻辑删除，按不存在处理，返回 `404`。

### 5.4 请求示例

```http
GET /api/devices/manage/1
```

### 5.5 响应示例

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "id": 1,
    "deviceId": "SL-001",
    "name": "南门-01",
    "area": "A区",
    "location": "106.5622,29.5621",
    "status": 1,
    "healthScore": 98.50,
    "topicPrefix": "streetlight",
    "lastHeartbeatAt": "2026-07-02T09:18:06",
    "enabled": true,
    "deleted": false
  }
}
```

## 6. 新增设备

### 6.1 接口

```http
POST /api/devices
Content-Type: application/json
```

### 6.2 请求体

新增和修改共用 `DeviceUpsertRequest`。

| 字段 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| `deviceId` | String | 是 | 设备唯一编号，不允许重复 |
| `name` | String | 是 | 设备名称 |
| `area` | String | 是 | 所属区域 |
| `location` | String | 否 | 安装位置或经纬度 |
| `status` | Integer | 否 | 设备状态：`0` 停用，`1` 在线，`2` 离线，`3` 异常；不传默认 `1` |
| `healthScore` | Decimal | 否 | 健康分，范围 `0.00` 到 `100.00`；不传默认 `100.00` |
| `topicPrefix` | String | 否 | MQTT 主题前缀；不传默认 `streetlight` |
| `lastHeartbeatAt` | DateTime | 否 | 最后心跳时间 |
| `latestData` | String | 否 | 最新数据快照 |
| `lastManualAt` | DateTime | 否 | 最后手动控制时间 |
| `enabled` | Boolean | 否 | 是否启用；不传默认 `true` |

### 6.3 参数校验

| 字段 | 校验规则 |
|---|---|
| `deviceId` | 不能为空 |
| `name` | 不能为空 |
| `area` | 不能为空 |
| `status` | 最小值 `0`，最大值 `3` |
| `healthScore` | 最小值 `0.00`，最大值 `100.00` |

### 6.4 业务规则

- 新增前校验 `deviceId` 是否已存在。
- 由于数据库存在 `uk_device_id` 唯一索引，逻辑删除后的设备编号仍然占用唯一键。
- 因此唯一性校验会查询包含已删除记录在内的设备数据，避免直接抛数据库唯一索引异常。
- 新增时强制设置：

```text
deleted = false
```

### 6.5 请求示例

```json
{
  "deviceId": "SL-007",
  "name": "东门-01",
  "area": "D区",
  "location": "106.5660,29.5660",
  "status": 1,
  "healthScore": 100.00,
  "topicPrefix": "streetlight",
  "enabled": true
}
```

### 6.6 响应示例

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "id": 7,
    "deviceId": "SL-007",
    "name": "东门-01",
    "area": "D区",
    "location": "106.5660,29.5660",
    "status": 1,
    "healthScore": 100.00,
    "topicPrefix": "streetlight",
    "enabled": true,
    "deleted": false
  }
}
```

### 6.7 常见错误

设备编号重复时返回业务错误：

```text
设备编号已存在
```

## 7. 修改设备

### 7.1 接口

```http
PUT /api/devices/{id}
Content-Type: application/json
```

### 7.2 请求参数

| 参数 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| `id` | Long | 是 | 设备数据库主键 |

请求体字段与新增设备一致。

### 7.3 业务规则

- 修改前先根据主键查询设备。
- 设备不存在或已逻辑删除时，返回 `404`。
- 如果修改了 `deviceId`，会校验新编号是否与其他设备冲突。
- 当前设备自身的 `deviceId` 不算重复。
- 修改后重新查询并返回最新设备数据。

### 7.4 请求示例

```http
PUT /api/devices/7
Content-Type: application/json
```

```json
{
  "deviceId": "SL-007",
  "name": "东门-01-已维护",
  "area": "D区",
  "location": "106.5660,29.5660",
  "status": 1,
  "healthScore": 96.50,
  "topicPrefix": "streetlight",
  "enabled": true
}
```

### 7.5 响应示例

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "id": 7,
    "deviceId": "SL-007",
    "name": "东门-01-已维护",
    "area": "D区",
    "location": "106.5660,29.5660",
    "status": 1,
    "healthScore": 96.50,
    "topicPrefix": "streetlight",
    "enabled": true,
    "deleted": false
  }
}
```

## 8. 删除设备

### 8.1 接口

```http
DELETE /api/devices/{id}
```

### 8.2 请求参数

| 参数 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| `id` | Long | 是 | 设备数据库主键 |

### 8.3 删除规则

设备删除采用逻辑删除，不做物理删除。

实际执行效果：

```text
deleted = true
```

逻辑删除后：

- 分页查询不再返回该设备。
- 管理详情接口按不存在处理。
- 数据库原始记录仍保留。
- `device_id` 唯一索引仍然占用，因此不能新增同编号设备。

### 8.4 请求示例

```http
DELETE /api/devices/7
```

### 8.5 响应示例

```json
{
  "code": 200,
  "msg": "success",
  "data": null
}
```

## 9. 核心实现说明

### 9.1 分页实现

分页使用 MyBatis-Plus `Page<Device>`。

项目中已配置分页插件：

```java
PaginationInnerInterceptor(DbType.MYSQL)
```

因此分页接口不需要手动拼接 `LIMIT`。

### 9.2 默认值处理

新增/修改时，如果请求没有传部分字段，服务层会写入默认值：

| 字段 | 默认值 |
|---|---|
| `status` | `1` |
| `healthScore` | `100.00` |
| `topicPrefix` | `streetlight` |
| `enabled` | `true` |
| `deleted` | 新增时固定为 `false` |

### 9.3 事务处理

新增、修改、删除方法均使用事务：

```java
@Transactional(rollbackFor = Exception.class)
```

保证设备台账写入失败时可以回滚。

### 9.4 唯一性校验

设备编号 `device_id` 在数据库中有唯一索引：

```text
uk_device_id(device_id)
```

为了避免新增或修改时出现数据库唯一键异常，服务层先调用：

```java
selectAnyByDeviceId(deviceId)
```

该查询不会过滤 `deleted` 字段，因此可以识别已逻辑删除但仍占用唯一索引的设备编号。

## 10. 与原有查询接口的关系

原有接口仍然保留：

| 接口 | 说明 |
|---|---|
| `/api/devices/list` | 原设备列表查询，返回数组，支持 `area/status` |
| `/api/devices/{deviceId}` | 原设备详情查询，按业务编号 `deviceId` 查询 |
| `/api/devices/statistics/status` | 状态统计 |
| `/api/devices/statistics/area-status` | 区域状态统计 |

本次新增的管理接口与原接口区别：

| 类型 | 查询方式 | 用途 |
|---|---|---|
| 原查询接口 | 按 `deviceId` 或普通列表 | 展示、统计、大屏查询 |
| 新管理接口 | 按数据库主键 `id` | 后台管理、编辑、删除、分页维护 |

## 11. 验证记录

已执行后端编译验证：

```bash
mvn -q -DskipTests compile
```

验证说明：

- 当前系统默认 Maven 使用 JDK 17，项目配置为 Java 21，会报“不支持发行版本 21”。
- 切换到本机 JDK 22 后编译通过。

实际验证命令：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-22'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -DskipTests compile
```

结果：编译通过。

## 12. 交付结论

本次后端设备管理 CRUD 已完成：

- `Create`：新增设备，支持必填校验、状态校验、健康分校验、编号唯一校验。
- `Read`：支持分页查询和按主键查询详情。
- `Update`：支持按主键修改设备，并校验设备存在性和编号唯一性。
- `Delete`：支持按主键逻辑删除设备。
- `Pagination`：分页查询支持关键字、区域、状态、启用状态过滤。

该接口集可直接支撑后续设备管理页面或接口联调。
