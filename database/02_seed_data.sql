-- ============================================================
-- 智慧路灯系统 · 基础种子数据
-- 执行前请确保已运行 01_init_schema.sql
-- 注意: 请先在 IDEA 中选中 smart_lighting_db 库，再运行本脚本
-- 适用于开发和测试环境
-- ============================================================

-- ============================================================
-- 1. device — 设备数据（6 盏路灯，覆盖不同区域和状态）
-- ============================================================
INSERT INTO device (device_id, name, area, location, status, health_score, topic_prefix, last_heartbeat_at, enabled, deleted) VALUES
('SL_001', '南门-01', 'A区', '106.5622,29.5621', 1, 98.50, 'streetlight', NOW() - INTERVAL 5 SECOND, 1, 0),
('SL_002', '南门-02', 'A区', '106.5625,29.5623', 1, 95.00, 'streetlight', NOW() - INTERVAL 8 SECOND, 1, 0),
('SL_003', '图书馆-01', 'B区', '106.5630,29.5630', 1, 100.00, 'streetlight', NOW() - INTERVAL 3 SECOND, 1, 0),
('SL_004', '图书馆-02', 'B区', '106.5633,29.5632', 2, 45.00, 'streetlight', NOW() - INTERVAL 5 MINUTE, 1, 0),
('SL_005', '操场-01', 'C区', '106.5610,29.5640', 3, 30.50, 'streetlight', NOW() - INTERVAL 30 MINUTE, 1, 0),
('SL_006', '北门-01', 'C区', '106.5600,29.5650', 0, 0.00, 'streetlight', NULL, 0, 0);

-- ============================================================
-- 2. telemetry — 遥测数据（每盏在线设备各 2 条）
-- ============================================================
INSERT INTO telemetry (device_id, illuminance, temperature, humidity, pm25, aqi, pir, traffic_flow, collected_at) VALUES
-- SL_001
('SL_001', 450.00, 28.50, 65.20, 35.00, 52, 1, 12, NOW() - INTERVAL 1 MINUTE),
('SL_001', 320.00, 28.30, 66.00, 33.00, 50, 0, 5, NOW() - INTERVAL 2 MINUTE),
-- SL_002
('SL_002', 520.00, 29.00, 63.50, 40.00, 58, 0, 3, NOW() - INTERVAL 1 MINUTE),
('SL_002', 480.00, 28.80, 64.00, 38.00, 55, 1, 8, NOW() - INTERVAL 2 MINUTE),
-- SL_003
('SL_003', 600.00, 27.50, 60.00, 28.00, 45, 0, 0, NOW() - INTERVAL 1 MINUTE),
('SL_003', 580.00, 27.60, 60.50, 30.00, 46, 0, 0, NOW() - INTERVAL 2 MINUTE),
-- SL_004（离线，最后上报的数据）
('SL_004', 200.00, 26.00, 70.00, 25.00, 42, 0, 0, NOW() - INTERVAL 6 MINUTE),
('SL_004', 180.00, 25.80, 71.00, 26.00, 43, 0, 0, NOW() - INTERVAL 7 MINUTE);

-- ============================================================
-- 3. control_command — 控制指令（近期操作记录）
-- ============================================================
INSERT INTO control_command (device_id, action, brightness, source, operator, status, issued_at, ack_at, result_detail) VALUES
('SL_001', 'ON', 100, 'AUTO', NULL, 'ACKED', NOW() - INTERVAL 2 HOUR, NOW() - INTERVAL 1 HOUR, 'success'),
('SL_002', 'ON', 100, 'AUTO', NULL, 'ACKED', NOW() - INTERVAL 2 HOUR, NOW() - INTERVAL 1 HOUR, 'success'),
('SL_003', 'ON', 80, 'MANUAL', 'admin', 'ACKED', NOW() - INTERVAL 30 MINUTE, NOW() - INTERVAL 29 MINUTE, 'brightness adjusted'),
('SL_004', 'OFF', NULL, 'MANUAL', 'admin', 'SENT', NOW() - INTERVAL 10 MINUTE, NULL, 'device unreachable');

-- ============================================================
-- 4. alarm_record — 告警记录
-- ============================================================
INSERT INTO alarm_record (device_id, type, level, status, reason, start_at, recover_at, handler) VALUES
('SL_004', 'OFFLINE', 'MAJOR', 'ACTIVE', '心跳中断超过 5 分钟', NOW() - INTERVAL 5 MINUTE, NULL, NULL),
('SL_005', 'HEALTH_LOW', 'CRITICAL', 'ACTIVE', '健康评分降至 30.50，低于阈值 60', NOW() - INTERVAL 30 MINUTE, NULL, NULL),
('SL_006', 'OFFLINE', 'MAJOR', 'RECOVERED', '设备手动停用', NOW() - INTERVAL 7 DAY, NOW() - INTERVAL 6 DAY, 'admin');

-- ============================================================
-- 5. lighting_policy — 照明策略
-- ============================================================
INSERT INTO lighting_policy (name, policy_type, conditions, action, priority, enabled, deleted, effective_time) VALUES
('光照低自动开灯', 'THRESHOLD', '{"lux_lt": 50}', 'ON', 1, 1, 0, '全天'),
('光照高自动关灯', 'THRESHOLD', '{"lux_gt": 200}', 'OFF', 2, 1, 0, '全天'),
('深夜节能调光', 'TIME', '{"time_range": "23:00-05:59"}', 'DIMMING(30)', 10, 1, 0, '23:00 ~ 06:00');
