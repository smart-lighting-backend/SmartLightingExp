# SmartLightingExp

智能照明实验项目 — 基于 Spring Boot 的照明控制系统后端服务。

## 技术栈

- **Java 21**
- **Spring Boot 4.1.0**
- **MySQL 8.4** (阿里云 RDS)
- **Maven** 构建

## 项目结构

```
src/
├── main/
│   ├── java/com/experiment/smartlightingexp/
│   │   └── SmartLightingExpApplication.java
│   └── resources/
│       └── application.yaml
└── test/
    └── java/com/experiment/smartlightingexp/
        └── SmartLightingExpApplicationTests.java
```

## 快速开始

### 前置要求

- JDK 21
- Maven 3.9+
- 阿里云 RDS MySQL 实例

### 配置数据库

`application.yaml` 中已配置数据库连接，密码通过环境变量 `DB_PASSWORD` 注入：

```yaml
spring:
  datasource:
    url: jdbc:mysql://rm-bp1vhdjtj6xp496p09o.mysql.rds.aliyuncs.com:3306/smart_lighting?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: app_user
    password: ${DB_PASSWORD}
```

启动前设置环境变量：

```bash
# Windows
set DB_PASSWORD=your_password

# Mac / Linux
export DB_PASSWORD=your_password
```

或在 IDEA Run Configuration 的 Environment variables 中添加 `DB_PASSWORD=your_password`。

### 运行

```bash
./mvnw spring-boot:run
```

## 构建

```bash
./mvnw clean package
java -jar target/SmartLightingExp-0.0.1-SNAPSHOT.jar
```
