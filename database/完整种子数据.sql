-- ============================================================
-- 智慧路灯系统 · 权限/菜单/角色 完整种子数据
-- 执行前需确保已执行 05_user_table.sql（建表）
-- ============================================================

-- ============================================================
-- 1. 清理旧数据（依赖关系：先删子表）
-- ============================================================
DELETE FROM `role_permission`;
DELETE FROM `permission`;
DELETE FROM `menu`;

-- ============================================================
-- 2. 角色表（已在 05_user_table.sql 中建表，此处仅插入）
-- ============================================================
DELETE FROM `role` WHERE id > 0;
INSERT INTO `role` (name, role_code, description) VALUES
('超级管理员', 'SUPER_ADMIN', '系统超级管理员，拥有所有权限，负责系统配置和用户管理'),
('市政人员',   'MUNICIPAL',   '查看道路照明、环境和能耗指标；配置阈值、策略和场景；处理异常告警'),
('路灯管理员', 'MAINTENANCE', '维护设备台账、状态、告警、工单和知识库；执行现场维修与恢复确认'),
('安全/应急人员', 'EMERGENCY', '接收异常事件、紧急求助和故障联动通知');

-- ============================================================
-- 3. 权限表（树形结构）
-- ============================================================

-- 3.1 模块级权限（11 个 MODULE，parent_id = NULL）
INSERT INTO `permission` (parent_id, name, permission_code, type, description) VALUES
(NULL, '数字孪生', 'dashboard',  'MODULE', '大屏概览、设备分布、在线率等聚合数据'),
(NULL, '设备管理', 'device',     'MODULE', '设备增删改查、控制和管理'),
(NULL, '数据报表', 'telemetry',  'MODULE', '查看遥测数据和历史趋势'),
(NULL, '能耗走势', 'energy',     'MODULE', '查看能耗报表和节能数据'),
(NULL, '告警中心', 'alarm',      'MODULE', '查看和处理系统告警'),
(NULL, '策略配置', 'policy',     'MODULE', '策略增删改查和启停管理'),
(NULL, '智能助手', 'assistant',  'MODULE', 'AI 智能问答和知识库'),
(NULL, '系统日志', 'audit',      'MODULE', '查看操作审计日志'),
(NULL, '用户管理', 'user',       'MODULE', '用户增删改查和角色分配'),
(NULL, '权限管理', 'permission', 'MODULE', '权限条目管理'),
(NULL, '菜单管理', 'menu',       'MODULE', '动态菜单配置');

-- 3.2 设备管理操作级权限（5 个）
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '设备查看', 'device:read',    'ACTION', '查看设备列表和详情'   FROM `permission` p WHERE p.permission_code = 'device';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '设备新增', 'device:create',  'ACTION', '新增设备'             FROM `permission` p WHERE p.permission_code = 'device';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '设备编辑', 'device:update',  'ACTION', '修改设备信息'         FROM `permission` p WHERE p.permission_code = 'device';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '设备删除', 'device:delete',  'ACTION', '删除设备'             FROM `permission` p WHERE p.permission_code = 'device';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '设备控制', 'device:control', 'ACTION', '手动控制设备开关调光' FROM `permission` p WHERE p.permission_code = 'device';

-- 3.3 告警中心操作级权限（3 个）
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '告警查看', 'alarm:read',   'ACTION', '查看告警列表'   FROM `permission` p WHERE p.permission_code = 'alarm';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '告警处理', 'alarm:handle', 'ACTION', '确认/处理告警'  FROM `permission` p WHERE p.permission_code = 'alarm';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '告警删除', 'alarm:delete', 'ACTION', '删除告警'       FROM `permission` p WHERE p.permission_code = 'alarm';

-- 3.4 策略配置操作级权限（4 个）
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '策略查看', 'policy:read',    'ACTION', '查看策略列表' FROM `permission` p WHERE p.permission_code = 'policy';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '策略新增', 'policy:create',  'ACTION', '新增照明策略' FROM `permission` p WHERE p.permission_code = 'policy';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '策略编辑', 'policy:update',  'ACTION', '修改策略'     FROM `permission` p WHERE p.permission_code = 'policy';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '策略删除', 'policy:delete',  'ACTION', '删除策略'     FROM `permission` p WHERE p.permission_code = 'policy';

-- 3.5 用户管理操作级权限（4 个）
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '用户查看', 'user:read',    'ACTION', '查看用户列表' FROM `permission` p WHERE p.permission_code = 'user';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '用户新增', 'user:create',  'ACTION', '新增用户'     FROM `permission` p WHERE p.permission_code = 'user';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '用户编辑', 'user:update',  'ACTION', '修改用户'     FROM `permission` p WHERE p.permission_code = 'user';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '用户删除', 'user:delete',  'ACTION', '删除用户'     FROM `permission` p WHERE p.permission_code = 'user';

-- 3.6 只读模块操作级权限（各 1 个）
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '大屏查看', 'dashboard:read',  'ACTION', '查看数字孪生大屏'    FROM `permission` p WHERE p.permission_code = 'dashboard';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '遥测查看', 'telemetry:read',  'ACTION', '查看遥测数据和历史趋势' FROM `permission` p WHERE p.permission_code = 'telemetry';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '能耗查看', 'energy:read',     'ACTION', '查看能耗报表和节能数据' FROM `permission` p WHERE p.permission_code = 'energy';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '助手使用', 'assistant:read',  'ACTION', '使用智能助手'          FROM `permission` p WHERE p.permission_code = 'assistant';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '日志查看', 'audit:read',      'ACTION', '查看审计日志'          FROM `permission` p WHERE p.permission_code = 'audit';

-- 3.7 权限管理操作级权限（4 个）
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '权限查看', 'permission:read',    'ACTION', '查看权限列表' FROM `permission` p WHERE p.permission_code = 'permission';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '权限新增', 'permission:create',  'ACTION', '新增权限'     FROM `permission` p WHERE p.permission_code = 'permission';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '权限编辑', 'permission:update',  'ACTION', '修改权限'     FROM `permission` p WHERE p.permission_code = 'permission';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '权限删除', 'permission:delete',  'ACTION', '删除权限'     FROM `permission` p WHERE p.permission_code = 'permission';

-- 3.8 菜单管理操作级权限（4 个）
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '菜单查看', 'menu:read',    'ACTION', '查看菜单列表' FROM `permission` p WHERE p.permission_code = 'menu';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '菜单新增', 'menu:create',  'ACTION', '新增菜单'     FROM `permission` p WHERE p.permission_code = 'menu';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '菜单编辑', 'menu:update',  'ACTION', '修改菜单'     FROM `permission` p WHERE p.permission_code = 'menu';
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '菜单删除', 'menu:delete',  'ACTION', '删除菜单'     FROM `permission` p WHERE p.permission_code = 'menu';

-- ============================================================
-- 4. 菜单表
-- ============================================================

-- 4.1 一级菜单（9 个）
INSERT INTO `menu` (parent_id, name, permission_code, icon, path, component, sort, enabled) VALUES
(NULL, '数字孪生', 'dashboard',   'grid',     '/dashboard',  'dashboard/index',  1, 1),
(NULL, '设备管理', 'device',      'bulb',     '/devices',    'device/index',     2, 1),
(NULL, '数据报表', 'telemetry',   'chart',    '/analytics',  'analytics/index',  3, 1),
(NULL, '能耗走势', 'energy',      'chart',    '/energy',     'energy/index',     4, 1),
(NULL, '告警中心', 'alarm',       'warning',  '/warning',    'warning/index',    5, 1),
(NULL, '策略配置', 'policy',      'strategy', '/strategy',   'strategy/index',   6, 1),
(NULL, '智能助手', 'assistant',   'robot',    '/assistant',  'assistant/index',  7, 1),
(NULL, '系统日志', 'audit',       'history',  '/logs',       'logs/index',       8, 1),
(NULL, '用户管理', 'user',        'user',     '/users',      'system/user/index',9, 1),
(NULL, '系统管理', NULL,          'setting',  '/system',     NULL,              10, 1);

-- 4.2 系统管理子菜单（2 个）
INSERT INTO `menu` (parent_id, name, permission_code, icon, path, component, sort, enabled)
SELECT m.id, '权限管理', 'permission', 'permission', '/system/permission', 'system/permission/index', 1, 1 FROM `menu` m WHERE m.path = '/system';
INSERT INTO `menu` (parent_id, name, permission_code, icon, path, component, sort, enabled)
SELECT m.id, '菜单管理', 'menu',       'menu',       '/system/menu',       'system/menu/index',       2, 1 FROM `menu` m WHERE m.path = '/system';

-- ============================================================
-- 5. 角色-权限分配
-- ============================================================

-- 5.1 SUPER_ADMIN（超级管理员）：所有权限
INSERT INTO `role_permission` (role_id, permission_id)
SELECT r.id, p.id FROM `role` r, `permission` p WHERE r.role_code = 'SUPER_ADMIN';

-- 5.2 MUNICIPAL（市政人员）：大屏/设备查看控制/遥测/能耗/告警处理/策略查看/助手/日志
INSERT INTO `role_permission` (role_id, permission_id)
SELECT r.id, p.id FROM `role` r, `permission` p
WHERE r.role_code = 'MUNICIPAL'
  AND p.permission_code IN (
    'dashboard', 'dashboard:read',
    'device', 'device:read', 'device:control',
    'telemetry', 'telemetry:read',
    'energy', 'energy:read',
    'alarm', 'alarm:read', 'alarm:handle',
    'policy', 'policy:read',
    'assistant', 'assistant:read',
    'audit', 'audit:read'
  );

-- 5.3 MAINTENANCE（路灯管理员）：大屏/设备全部/遥测/能耗/告警全部/策略查看/助手
INSERT INTO `role_permission` (role_id, permission_id)
SELECT r.id, p.id FROM `role` r, `permission` p
WHERE r.role_code = 'MAINTENANCE'
  AND p.permission_code IN (
    'dashboard', 'dashboard:read',
    'device', 'device:read', 'device:create', 'device:update', 'device:delete',
    'telemetry', 'telemetry:read',
    'energy', 'energy:read',
    'alarm', 'alarm:read', 'alarm:handle', 'alarm:delete',
    'policy', 'policy:read',
    'assistant', 'assistant:read'
  );

-- 5.4 EMERGENCY（安全/应急人员）：大屏/告警查看/助手
INSERT INTO `role_permission` (role_id, permission_id)
SELECT r.id, p.id FROM `role` r, `permission` p
WHERE r.role_code = 'EMERGENCY'
  AND p.permission_code IN (
    'dashboard', 'dashboard:read',
    'alarm', 'alarm:read',
    'assistant', 'assistant:read'
  );

-- ============================================================
-- 验证查询
-- ============================================================
-- SELECT r.name 角色, COUNT(rp.id) 权限数
-- FROM `role` r
-- LEFT JOIN `role_permission` rp ON rp.role_id = r.id
-- GROUP BY r.id ORDER BY r.id;
-- 
-- 预期结果：
-- 超级管理员 → 41
-- 市政人员   → 18
-- 路灯管理员 → 19
-- 安全/应急   → 5
