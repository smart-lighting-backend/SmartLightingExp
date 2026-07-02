-- ============================================================
-- 智慧路灯系统 · 数据库增量变更脚本（V2.0）
-- 适用版本: 在 01_init_schema.sql 基础上执行
-- ============================================================

-- ============================================================
-- 1. device — 新增 latest_data 字段
--    用于前端实时展示设备最新遥测，避免频繁查询 telemetry 流水表
-- ============================================================
ALTER TABLE device
    ADD COLUMN latest_data JSON DEFAULT NULL COMMENT '最新遥测快照（冗余字段，前端展示用）'
    AFTER last_heartbeat_at;


-- ============================================================
-- 2. vision_event — AI 视觉识别事件表
--    记录摄像头/视觉服务的识别结果（行人、车辆、异常停留等），
--    供联动控制、安防告警和大屏事件展示使用。
-- ============================================================
CREATE TABLE vision_event (
    id                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    device_id         VARCHAR(50)     NOT NULL                 COMMENT '设备ID',
    event_type        VARCHAR(30)     NOT NULL                 COMMENT '事件类型：PERSON/VEHICLE/ABNORMAL_STAY/INTRUSION',
    confidence        DECIMAL(5,2)    DEFAULT NULL             COMMENT '识别置信度（0~100）',
    snapshot_ref      VARCHAR(255)    DEFAULT NULL             COMMENT '抓拍快照引用路径/URL',
    occurred_at       DATETIME(3)     NOT NULL                 COMMENT '事件发生时间',
    create_time       DATETIME(3)     DEFAULT CURRENT_TIMESTAMP(3) COMMENT '系统入库时间',
    PRIMARY KEY (id),
    KEY idx_device_occurred (device_id, occurred_at),
    KEY idx_event_type (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 视觉识别事件表';


-- ============================================================
-- 3. voice_event — 语音交互事件表
--    记录语音播报、紧急求助、人机交互记录等。
-- ============================================================
CREATE TABLE voice_event (
    id                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    device_id         VARCHAR(50)     NOT NULL                 COMMENT '设备ID',
    type              VARCHAR(20)     NOT NULL                 COMMENT '类型：BROADCAST/SOS/INTERACT',
    content           VARCHAR(500)    DEFAULT NULL             COMMENT '语音内容文本',
    source            VARCHAR(20)     DEFAULT 'DEVICE'         COMMENT '来源：DEVICE/MANUAL/SYSTEM',
    occurred_at       DATETIME(3)     NOT NULL                 COMMENT '事件发生时间',
    create_time       DATETIME(3)     DEFAULT CURRENT_TIMESTAMP(3) COMMENT '系统入库时间',
    PRIMARY KEY (id),
    KEY idx_device_occurred (device_id, occurred_at),
    KEY idx_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='语音交互事件表';


-- ============================================================
-- 4. energy_record — 能耗统计表
--    按设备 × 日粒度记录亮灯时长、平均亮度、估算能耗和碳减排，
--    为节能报表和绿色低碳指标提供数据。
-- ============================================================
CREATE TABLE energy_record (
    id                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    device_id         VARCHAR(50)     NOT NULL                 COMMENT '设备ID',
    record_date       DATE            NOT NULL                 COMMENT '统计日期',
    on_duration_min   INT             DEFAULT 0                COMMENT '亮灯时长（分钟）',
    avg_brightness    DECIMAL(5,2)    DEFAULT NULL             COMMENT '平均亮度（0~100）',
    estimated_kwh     DECIMAL(10,4)   DEFAULT NULL             COMMENT '估算用电量（千瓦时）',
    saving_rate       DECIMAL(5,2)    DEFAULT NULL             COMMENT '节能率（%）',
    carbon_reduction  DECIMAL(10,4)   DEFAULT NULL             COMMENT '碳减排估算（kg CO₂）',
    create_time       DATETIME(3)     DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_device_date (device_id, record_date),
    KEY idx_record_date (record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='能耗统计表';


-- ============================================================
-- 5. audit_log — 操作审计日志表
--    记录所有高风险操作（登录、设备增删、阈值修改、策略发布、控制指令等），
--    满足安全可信控制（IR-11）的溯源要求。
-- ============================================================
CREATE TABLE audit_log (
    id                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    operator          VARCHAR(50)     NOT NULL                 COMMENT '操作人用户名',
    action            VARCHAR(30)     NOT NULL                 COMMENT '操作类型：LOGIN/DEVICE_ADD/DEVICE_DELETE/THRESHOLD_SET/CONTROL_ON/CONTROL_OFF/DIMMING/POLICY_UPDATE',
    target_type       VARCHAR(20)     NOT NULL                 COMMENT '操作对象类型：DEVICE/POLICY/THRESHOLD/SYSTEM',
    target_id         VARCHAR(50)     DEFAULT NULL             COMMENT '操作对象ID（如设备ID、策略ID）',
    detail            VARCHAR(500)    DEFAULT NULL             COMMENT '操作详情描述',
    result            VARCHAR(10)     DEFAULT 'SUCCESS'        COMMENT '结果：SUCCESS/FAIL',
    ip_address        VARCHAR(50)     DEFAULT NULL             COMMENT '操作人IP',
    operated_at       DATETIME(3)     NOT NULL                 COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_operator_time (operator, operated_at),
    KEY idx_action_time (action, operated_at),
    KEY idx_operated_at (operated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作审计日志表';
