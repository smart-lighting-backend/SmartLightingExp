-- ============================================================
-- 智慧路灯系统 · 审计日志种子数据
-- 覆盖各模块操作，确保 SystemLog 页面开发时有数据可见
-- ============================================================

INSERT INTO audit_log (operator, action, target_type, target_id, detail, result, ip_address, operated_at) VALUES
-- 认证登录
('admin', 'LOGIN', 'SYSTEM', NULL, '登录成功-角色:SUPER_ADMIN', 'SUCCESS', '192.168.1.100', NOW() - INTERVAL 2 HOUR),
('admin', 'LOGIN', 'SYSTEM', NULL, '登录失败-密码错误', 'FAIL', '192.168.1.100', NOW() - INTERVAL 3 HOUR),

-- 设备管理
('admin', 'DEVICE_CREATE', 'DEVICE', '7', '新增设备-SL-007', 'SUCCESS', '192.168.1.100', NOW() - INTERVAL 4 HOUR),
('admin', 'DEVICE_UPDATE', 'DEVICE', 'SL-003', '更新设备-图书馆-01', 'SUCCESS', '192.168.1.100', NOW() - INTERVAL 5 HOUR),
('admin', 'DEVICE_DELETE', 'DEVICE', 'SL-006', '删除设备-北门-01', 'SUCCESS', '192.168.1.100', NOW() - INTERVAL 6 HOUR),

-- 设备控制
('admin', 'CONTROL', 'DEVICE', 'SL-001', '手动控制-ON', 'SUCCESS', '192.168.1.100', NOW() - INTERVAL 1 HOUR),
('admin', 'CONTROL', 'DEVICE', 'SL-002', '手动控制-DIMMING(70)', 'SUCCESS', '192.168.1.100', NOW() - INTERVAL 90 MINUTE),

-- 照明策略
('admin', 'THRESHOLD_SET', 'THRESHOLD', '1', '设置光照阈值-lux_lt=50,lux_gt=200', 'SUCCESS', '192.168.1.100', NOW() - INTERVAL 8 HOUR),
('admin', 'POLICY_CREATE', 'POLICY', '3', '新增策略-深夜节能调光', 'SUCCESS', '192.168.1.100', NOW() - INTERVAL 1 DAY),
('admin', 'POLICY_TOGGLE', 'POLICY', '2', '禁用策略-光照联动自动开关', 'SUCCESS', '192.168.1.100', NOW() - INTERVAL 12 HOUR),

-- 告警管理
('admin', 'ALARM_CREATE', 'ALARM', '4', '新增告警-OFFLINE:心跳中断超过300秒', 'SUCCESS', '192.168.1.100', NOW() - INTERVAL 30 MINUTE),
('admin', 'ALARM_HANDLE', 'ALARM', '4', '处理告警-OFFLINE:心跳中断超过300秒', 'SUCCESS', '192.168.1.100', NOW() - INTERVAL 15 MINUTE),

-- 用户管理
('admin', 'USER_CREATE', 'USER', '2', '新增用户-zhang', 'SUCCESS', '192.168.1.100', NOW() - INTERVAL 2 DAY),
('admin', 'USER_UPDATE', 'USER', '2', '更新用户-zhang', 'SUCCESS', '192.168.1.100', NOW() - INTERVAL 1 DAY),

-- 角色管理
('admin', 'ROLE_CREATE', 'ROLE', '2', '新增角色-市政人员', 'SUCCESS', '192.168.1.100', NOW() - INTERVAL 3 DAY),
('admin', 'ROLE_PERMISSION', 'ROLE', '2', '分配权限-市政人员: 8项权限', 'SUCCESS', '192.168.1.100', NOW() - INTERVAL 3 DAY),

-- 权限管理
('admin', 'PERM_CREATE', 'PERMISSION', '5', '新增权限-能耗查看', 'SUCCESS', '192.168.1.100', NOW() - INTERVAL 4 DAY),
('admin', 'PERM_DELETE', 'PERMISSION', '6', '删除权限-旧版告警配置', 'SUCCESS', '192.168.1.100', NOW() - INTERVAL 4 DAY);
