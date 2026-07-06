 -- ============================================================
-- 智慧路灯系统 · 数据库增量变更脚本（V3.0）
-- 适用版本: 在 05_user_table.sql 基础上执行
-- 变更内容: ① permission 表新增 parent_id/type 支持树形层级
--           ② 新增 menu 动态菜单表
--           ③ 模块：数字孪生/设备/报表/能耗/告警/策略/助手/日志/用户/权限/菜单
-- =====================================‘=======================

-- ============================================================
-- 1. permission 表结构变更
-- ============================================================
ALTER TABLE `permission`
    ADD COLUMN `parent_id`    BIGINT      DEFAULT NULL COMMENT '父权限ID（NULL=模块级）' AFTER `id`,
    ADD COLUMN `type`         VARCHAR(10) DEFAULT 'ACTION' COMMENT 'MODULE-模块, ACTION-操作' AFTER `description`,
    ADD INDEX idx_parent_id (parent_id);

UPDATE `permission` SET `type` = 'MODULE' WHERE `parent_id` IS NULL;

-- ============================================================
-- 2. menu 动态菜单表
-- ============================================================
CREATE TABLE IF NOT EXISTS `menu` (
    id                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    parent_id         BIGINT          DEFAULT NULL             COMMENT '父菜单ID',
    name              VARCHAR(50)     NOT NULL                 COMMENT '菜单名称',
    permission_code   VARCHAR(50)     DEFAULT NULL             COMMENT '关联权限编码',
    icon              VARCHAR(50)     DEFAULT NULL             COMMENT '图标名称',
    path              VARCHAR(100)    DEFAULT NULL             COMMENT '前端路由路径',
    component         VARCHAR(100)    DEFAULT NULL             COMMENT '前端组件路径',
    sort              INT             DEFAULT 0                COMMENT '排序号',
    enabled           TINYINT(1)      DEFAULT 1                COMMENT '是否启用',
    create_time       DATETIME(3)     DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='动态菜单表';

-- ============================================================
-- 3. 重建权限数据（先清空旧数据，再插入新结构）
-- ============================================================
DELETE FROM `role_permission`;
DELETE FROM `permission`;

-- 模块级权限
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
(NULL, '菜单管理', 'menu',       'MODULE', '动态菜单配置'),
(NULL, '角色管理', 'role',       'MODULE', '角色增删改查和权限分配');

-- 操作级子权限
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '设备新增', 'device:create', 'ACTION', '新增设备' FROM `permission` p WHERE p.permission_code = 'device'
UNION ALL
SELECT p.id, '设备查看', 'device:read',   'ACTION', '查看设备列表和详情' FROM `permission` p WHERE p.permission_code = 'device'
UNION ALL
SELECT p.id, '设备编辑', 'device:update', 'ACTION', '修改设备信息' FROM `permission` p WHERE p.permission_code = 'device'
UNION ALL
SELECT p.id, '设备删除', 'device:delete', 'ACTION', '删除设备' FROM `permission` p WHERE p.permission_code = 'device'
UNION ALL
SELECT p.id, '设备控制', 'device:control','ACTION', '手动控制设备开关调光' FROM `permission` p WHERE p.permission_code = 'device'
UNION ALL
SELECT p.id, '告警查看', 'alarm:read',    'ACTION', '查看告警列表' FROM `permission` p WHERE p.permission_code = 'alarm'
UNION ALL
SELECT p.id, '告警处理', 'alarm:handle',  'ACTION', '确认/处理告警' FROM `permission` p WHERE p.permission_code = 'alarm'
UNION ALL
SELECT p.id, '告警删除', 'alarm:delete',  'ACTION', '删除告警' FROM `permission` p WHERE p.permission_code = 'alarm'
UNION ALL
SELECT p.id, '策略查看', 'policy:read',   'ACTION', '查看策略列表' FROM `permission` p WHERE p.permission_code = 'policy'
UNION ALL
SELECT p.id, '策略新增', 'policy:create', 'ACTION', '新增照明策略' FROM `permission` p WHERE p.permission_code = 'policy'
UNION ALL
SELECT p.id, '策略编辑', 'policy:update', 'ACTION', '修改策略' FROM `permission` p WHERE p.permission_code = 'policy'
UNION ALL
SELECT p.id, '策略删除', 'policy:delete', 'ACTION', '删除策略' FROM `permission` p WHERE p.permission_code = 'policy'
UNION ALL
SELECT p.id, '用户查看', 'user:read',     'ACTION', '查看用户列表' FROM `permission` p WHERE p.permission_code = 'user'
UNION ALL
SELECT p.id, '用户新增', 'user:create',   'ACTION', '新增用户' FROM `permission` p WHERE p.permission_code = 'user'
UNION ALL
SELECT p.id, '用户编辑', 'user:update',   'ACTION', '修改用户' FROM `permission` p WHERE p.permission_code = 'user'
UNION ALL
SELECT p.id, '用户删除', 'user:delete',   'ACTION', '删除用户' FROM `permission` p WHERE p.permission_code = 'user';

-- 只读模块的子权限
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '大屏查看', 'dashboard:read',  'ACTION', '查看数字孪生大屏' FROM `permission` p WHERE p.permission_code = 'dashboard'
UNION ALL
SELECT p.id, '遥测查看', 'telemetry:read',  'ACTION', '查看遥测数据和历史趋势' FROM `permission` p WHERE p.permission_code = 'telemetry'
UNION ALL
SELECT p.id, '能耗查看', 'energy:read',     'ACTION', '查看能耗报表和节能数据' FROM `permission` p WHERE p.permission_code = 'energy'
UNION ALL
SELECT p.id, '助手使用', 'assistant:read',  'ACTION', '使用智能助手' FROM `permission` p WHERE p.permission_code = 'assistant'
UNION ALL
SELECT p.id, '日志查看', 'audit:read',      'ACTION', '查看审计日志' FROM `permission` p WHERE p.permission_code = 'audit'
UNION ALL
SELECT p.id, '权限查看', 'permission:read', 'ACTION', '查看权限列表' FROM `permission` p WHERE p.permission_code = 'permission'
UNION ALL
SELECT p.id, '菜单查看', 'menu:read',       'ACTION', '查看菜单列表' FROM `permission` p WHERE p.permission_code = 'menu';

-- 权限管理/菜单管理的写操作
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '权限新增', 'permission:create', 'ACTION', '新增权限' FROM `permission` p WHERE p.permission_code = 'permission'
UNION ALL
SELECT p.id, '权限编辑', 'permission:update', 'ACTION', '修改权限' FROM `permission` p WHERE p.permission_code = 'permission'
UNION ALL
SELECT p.id, '权限删除', 'permission:delete', 'ACTION', '删除权限' FROM `permission` p WHERE p.permission_code = 'permission'
UNION ALL
SELECT p.id, '菜单新增', 'menu:create', 'ACTION', '新增菜单' FROM `permission` p WHERE p.permission_code = 'menu'
UNION ALL
SELECT p.id, '菜单编辑', 'menu:update', 'ACTION', '修改菜单' FROM `permission` p WHERE p.permission_code = 'menu'
UNION ALL
SELECT p.id, '菜单删除', 'menu:delete', 'ACTION', '删除菜单' FROM `permission` p WHERE p.permission_code = 'menu'
UNION ALL
SELECT p.id, '角色查看', 'role:read',   'ACTION', '查看角色列表' FROM `permission` p WHERE p.permission_code = 'role'
UNION ALL
SELECT p.id, '角色新增', 'role:create', 'ACTION', '新增角色' FROM `permission` p WHERE p.permission_code = 'role'
UNION ALL
SELECT p.id, '角色编辑', 'role:update', 'ACTION', '修改角色' FROM `permission` p WHERE p.permission_code = 'role'
UNION ALL
SELECT p.id, '角色删除', 'role:delete', 'ACTION', '删除角色' FROM `permission` p WHERE p.permission_code = 'role'
UNION ALL
SELECT p.id, '角色授权', 'role:assign', 'ACTION', '为角色分配权限' FROM `permission` p WHERE p.permission_code = 'role';

-- ============================================================
-- 4. 菜单种子数据
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

-- 系统管理子菜单
INSERT INTO `menu` (parent_id, name, permission_code, icon, path, component, sort, enabled)
SELECT m.id, '权限管理', 'permission', 'permission', '/system/permission', 'system/permission/index', 1, 1 FROM `menu` m WHERE m.path = '/system'
UNION ALL
SELECT m.id, '菜单管理', 'menu',       'menu',       '/system/menu',       'system/menu/index',       2, 1 FROM `menu` m WHERE m.path = '/system';

-- ============================================================
-- 5. 角色-权限分配
-- ============================================================

-- SUPER_ADMIN：所有权限
INSERT INTO `role_permission` (role_id, permission_id)
SELECT r.id, p.id FROM `role` r, `permission` p WHERE r.role_code = 'SUPER_ADMIN';

-- MUNICIPAL（市政人员）：大屏/设备查看控制/报表/能耗/告警处理/策略查看/助手/日志
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

-- MAINTENANCE（路灯管理员）：大屏/设备全部/报表/能耗/告警全部/策略查看/助手
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

-- EMERGENCY（安全/应急人员）：仅大屏/告警查看/助手
INSERT INTO `role_permission` (role_id, permission_id)
SELECT r.id, p.id FROM `role` r, `permission` p
WHERE r.role_code = 'EMERGENCY'
  AND p.permission_code IN (
    'dashboard', 'dashboard:read',
    'alarm', 'alarm:read',
    'assistant', 'assistant:read'
  );
