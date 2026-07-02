-- ============================================================
-- 智慧路灯系统 · 数据库增量变更脚本（V2.1）
-- 适用版本: 在 03_schema_upgrade.sql 基础上执行
-- ============================================================

-- ============================================================
-- 6. decision_log — 策略决策日志表
--    记录策略引擎每次自动决策的输入条件、命中策略和执行结果，
--    满足 IR-04 "决策可追溯" 的验收要求。
--    与控制指令表（control_command）的关系：
--      - decision_log：记录 "为什么做这个决策"
--      - control_command：记录 "下了什么指令、设备执行没有"
-- ============================================================
CREATE TABLE decision_log (
    id                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    device_id         VARCHAR(50)     NOT NULL                 COMMENT '设备ID',
    input_snapshot    JSON            DEFAULT NULL             COMMENT '决策时刻的遥测快照（光照、PIR、流量、时段等输入条件）',
    matched_policy    VARCHAR(100)    DEFAULT NULL             COMMENT '命中的策略名称（多个策略用逗号分隔）',
    action_taken      VARCHAR(50)     DEFAULT NULL             COMMENT '执行动作：ON/OFF/DIMMING(30) / SKIP',
    result            VARCHAR(20)     NOT NULL                 COMMENT '结果：MATCH_EXECUTED / MATCH_SKIPPED / NO_MATCH',
    create_time       DATETIME(3)    DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录生成时间',
    PRIMARY KEY (id),
    KEY idx_device_time (device_id, create_time),
    KEY idx_result (result),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='策略决策日志表';
