-- ============================================================
-- 09_device_credential — 设备 MQTT 鉴权凭证表
-- 每台设备独立的 MQTT 用户名/密码，用于 EMQX 认证
-- ============================================================

CREATE TABLE device_credential (
    id                         BIGINT          AUTO_INCREMENT PRIMARY KEY,
    device_id                  VARCHAR(50)     NOT NULL COMMENT '设备ID，关联device表(device_id)',
    username                   VARCHAR(50)     NOT NULL COMMENT 'MQTT用户名（= deviceId，如SL_001）',
    password_hash              VARCHAR(255)    NOT NULL COMMENT 'BCrypt密码哈希（EMQX直接读取此列做验证）',
    factory_serial_encrypted   VARCHAR(255)    NOT NULL COMMENT '出厂编号(AES-256-CBC加密存储)',
    device_id_code_encrypted   VARCHAR(255)    NOT NULL COMMENT '设备识别码(AES-256-CBC加密，默认123456)',
    create_time                DATETIME(3)     DEFAULT CURRENT_TIMESTAMP(3),
    update_time                DATETIME(3)     DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_device_id (device_id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备MQTT鉴权凭证表';
