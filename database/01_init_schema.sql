-- ============================================================
-- 智慧路灯系统 · 数据库建表脚本
-- 数据库版本: MySQL 8.4
-- 数据库名:   smart_lighting_db
-- 注意: 请先在 IDEA 中手动创建 smart_lighting_db 库并选中它，再运行本脚本
-- ============================================================

-- ============================================================
-- 1. device — 设备台账表
--    记录每盏路灯的固定身份信息和实时状态。
-- ============================================================
CREATE TABLE device (
    id                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    device_id         VARCHAR(50)     NOT NULL                 COMMENT '设备唯一编号（对应MQTT Topic中的deviceId）',
    name              VARCHAR(100)    DEFAULT NULL             COMMENT '设备名称（如：南门-01）',
    area              VARCHAR(50)     DEFAULT NULL             COMMENT '区域（如：A区、B区）',
    location          VARCHAR(255)    DEFAULT NULL             COMMENT '安装位置经纬度或描述',
    status            TINYINT         DEFAULT 1                COMMENT '状态：0-停用，1-在线，2-离线，3-异常',
    health_score      DECIMAL(5,2)    DEFAULT 100.00           COMMENT '健康评分（0~100）',
    topic_prefix      VARCHAR(100)    DEFAULT 'streetlight'    COMMENT 'MQTT主题前缀',
    last_heartbeat_at DATETIME(3)     DEFAULT NULL             COMMENT '最后一次心跳时间',
    enabled           TINYINT(1)      DEFAULT 1                COMMENT '是否启用：0-停用，1-启用',
    deleted           TINYINT(1)      DEFAULT 0                COMMENT '逻辑删除：0-正常，1-已删除',
    create_time       DATETIME(3)     DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time       DATETIME(3)     DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_device_id (device_id),
    KEY idx_area (area),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备台账表';


-- ============================================================
-- 2. telemetry — 多模态遥测数据表
--    存储传感器上报的环境数据，历史趋势图的数据来源。
-- ============================================================
CREATE TABLE telemetry (
    id                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    device_id         VARCHAR(50)     NOT NULL                 COMMENT '设备ID',
    illuminance       DECIMAL(10,2)   DEFAULT NULL             COMMENT '光照强度(lux)',
    temperature       DECIMAL(5,2)    DEFAULT NULL             COMMENT '温度(℃)',
    humidity          DECIMAL(5,2)    DEFAULT NULL             COMMENT '湿度(%)',
    pm25              DECIMAL(5,2)    DEFAULT NULL             COMMENT 'PM2.5浓度',
    aqi               INT             DEFAULT NULL             COMMENT '空气质量指数',
    pir               TINYINT         DEFAULT 0                COMMENT '人体红外感应：0-无人，1-有人',
    traffic_flow      INT             DEFAULT 0                COMMENT '人/车流量（估算）',
    collected_at      DATETIME(3)     NOT NULL                 COMMENT '数据采集时间（设备时间戳）',
    create_time       DATETIME(3)     DEFAULT CURRENT_TIMESTAMP(3) COMMENT '系统入库时间',
    PRIMARY KEY (id),
    KEY idx_device_collected (device_id, collected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='多模态遥测数据表';


-- ============================================================
-- 3. control_command — 控制指令表
--    记录每次对路灯的控制操作，用于审计追溯。
-- ============================================================
CREATE TABLE control_command (
    id                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    device_id         VARCHAR(50)     NOT NULL                 COMMENT '设备ID',
    action            VARCHAR(20)     NOT NULL                 COMMENT '动作：ON/OFF/DIMMING',
    brightness        INT             DEFAULT NULL             COMMENT '亮度值（0~100）',
    source            VARCHAR(20)     DEFAULT 'MANUAL'         COMMENT '来源：MANUAL/AUTO/EMERGENCY',
    operator          VARCHAR(50)     DEFAULT NULL             COMMENT '操作人（手动时记录用户名）',
    status            VARCHAR(20)     DEFAULT 'SENT'           COMMENT '状态：SENT/ACKED/FAILED/TIMEOUT',
    issued_at         DATETIME(3)     NOT NULL                 COMMENT '指令下发时间',
    ack_at            DATETIME(3)     DEFAULT NULL             COMMENT '设备确认时间',
    result_detail     VARCHAR(255)    DEFAULT NULL             COMMENT '执行结果详情',
    PRIMARY KEY (id),
    KEY idx_device_issued (device_id, issued_at),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='控制指令表';


-- ============================================================
-- 4. alarm_record — 告警记录表
--    记录系统检测到的异常事件（离线、健康分过低等）。
-- ============================================================
CREATE TABLE alarm_record (
    id                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    device_id         VARCHAR(50)     NOT NULL                 COMMENT '设备ID',
    type              VARCHAR(30)     NOT NULL                 COMMENT '类型：OFFLINE/OVER_TEMP/VISION_ABNORMAL/HEALTH_LOW',
    level             VARCHAR(10)     DEFAULT 'MAJOR'          COMMENT '级别：MAJOR/MINOR/CRITICAL',
    status            VARCHAR(20)     DEFAULT 'ACTIVE'         COMMENT '状态：ACTIVE/RECOVERED/ACKNOWLEDGED',
    reason            VARCHAR(255)    DEFAULT NULL             COMMENT '触发原因',
    start_at          DATETIME(3)     NOT NULL                 COMMENT '告警开始时间',
    recover_at        DATETIME(3)     DEFAULT NULL             COMMENT '恢复时间',
    handler           VARCHAR(50)     DEFAULT NULL             COMMENT '处理人',
    PRIMARY KEY (id),
    KEY idx_device_start (device_id, start_at),
    KEY idx_type (type),
    KEY idx_level (level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警记录表';


-- ============================================================
-- 5. lighting_policy — 照明策略表
--    存储自适应节能的规则配置，策略引擎读取并匹配执行。
-- ============================================================
CREATE TABLE lighting_policy (
    id                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    name              VARCHAR(100)    NOT NULL                 COMMENT '策略名称',
    policy_type       VARCHAR(20)     NOT NULL                 COMMENT '类型：THRESHOLD/TIME/SCENE',
    conditions        JSON            DEFAULT NULL             COMMENT '触发条件JSON（如 {"lux_lt":50,"time_range":"22:00-06:00"}）',
    action            VARCHAR(50)     NOT NULL                 COMMENT '执行动作：ON/OFF/DIMMING(70)',
    priority          INT             DEFAULT 5                COMMENT '优先级（1最高，10最低）',
    enabled           TINYINT(1)      DEFAULT 1                COMMENT '是否启用：0-禁用，1-启用',
    deleted           TINYINT(1)      DEFAULT 0                COMMENT '逻辑删除：0-正常，1-已删除',
    effective_time    VARCHAR(100)    DEFAULT NULL             COMMENT '生效时间段描述（便于展示）',
    create_time       DATETIME(3)     DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_policy_type (policy_type),
    KEY idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='照明策略表';
