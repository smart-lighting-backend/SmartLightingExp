-- ============================================================
-- V3.1 设备分区管理
-- ============================================================

-- 1. 设备分区表（name 唯一，保证幂等）
CREATE TABLE IF NOT EXISTS device_area (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    name        VARCHAR(50)  NOT NULL                 COMMENT '区域名称（如 A区、B区、南门）',
    description VARCHAR(255) DEFAULT NULL             COMMENT '区域描述',
    parent_id   BIGINT       DEFAULT NULL             COMMENT '父区域ID，支持树形层级',
    enabled     TINYINT(1)   DEFAULT 1                COMMENT '是否启用',
    create_time DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name),
    KEY idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备分区表';

-- 2. device 表新增 area_id 外键（保留 area 字段作为冗余展示名）
ALTER TABLE device
    ADD COLUMN area_id BIGINT DEFAULT NULL COMMENT '关联 device_area.id'
    AFTER area;

-- 3. 将现有 device.area 数据迁移到 device_area 表，并建立关联
INSERT IGNORE INTO device_area (name, description)
SELECT DISTINCT area, CONCAT(area, '（由历史数据自动迁移）')
FROM device
WHERE area IS NOT NULL AND area != '';

UPDATE device d
    JOIN device_area da ON d.area = da.name
SET d.area_id = da.id
WHERE d.area_id IS NULL AND d.area IS NOT NULL AND d.area != '';

-- 4. 种子数据：预置区域层级（与现有设备南门/图书馆/操场/北门对应）
INSERT IGNORE INTO device_area (name, description, parent_id) VALUES
    ('A区', 'A区 — 主干道照明区域（南门）', NULL),
    ('B区', 'B区 — 次干道照明区域（图书馆）', NULL),
    ('C区', 'C区 — 公园/人行道照明区域（操场、北门）', NULL);

SET @area_a_id = (SELECT id FROM device_area WHERE name = 'A区');
SET @area_b_id = (SELECT id FROM device_area WHERE name = 'B区');
SET @area_c_id = (SELECT id FROM device_area WHERE name = 'C区');

INSERT IGNORE INTO device_area (name, description, parent_id) VALUES
    ('A区-南段', '南门区域 — 南门至十字路口', @area_a_id),
    ('A区-北段', '北门区域 — 十字路口至北门', @area_a_id),
    ('B区-图书馆', '图书馆周边区域', @area_b_id),
    ('B区-东段', 'B区东侧辅道', @area_b_id),
    ('C区-操场', '操场周边区域', @area_c_id),
    ('C区-北段', 'C区北侧路段', @area_c_id);

-- ============================================================
-- 5. 注册权限到 permission 表（挂载在 device 模块下）
-- ============================================================
-- 5a. 区域管理模块入口权限（用于菜单可见性判断）
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '区域管理', 'device_area', 'ACTION', '设备分区管理入口（控制菜单可见性）' FROM `permission` p WHERE p.permission_code = 'device';

-- 5b. 区域管理操作权限
INSERT INTO `permission` (parent_id, name, permission_code, type, description)
SELECT p.id, '区域查看', 'device_area:read',   'ACTION', '查看设备分区列表和树形结构' FROM `permission` p WHERE p.permission_code = 'device'
UNION ALL
SELECT p.id, '区域新增', 'device_area:create', 'ACTION', '新增设备分区' FROM `permission` p WHERE p.permission_code = 'device'
UNION ALL
SELECT p.id, '区域编辑', 'device_area:update', 'ACTION', '修改设备分区信息' FROM `permission` p WHERE p.permission_code = 'device'
UNION ALL
SELECT p.id, '区域删除', 'device_area:delete', 'ACTION', '删除设备分区' FROM `permission` p WHERE p.permission_code = 'device';

-- ============================================================
-- 6. 注册菜单（挂载在 设备管理 下作为二级菜单）
-- ============================================================
INSERT INTO `menu` (parent_id, name, permission_code, icon, path, component, sort, enabled)
SELECT m.id, '区域管理', 'device_area', 'map', '/devices/area', 'device/area/index', 1, 1 FROM `menu` m WHERE m.path = '/devices';

-- ============================================================
-- 7. 为角色分配区域管理权限
-- ============================================================

-- SUPER_ADMIN 已通过全量交叉获取所有权限，无需单独添加

-- MUNICIPAL：区域查看
INSERT INTO `role_permission` (role_id, permission_id)
SELECT r.id, p.id FROM `role` r, `permission` p
WHERE r.role_code = 'MUNICIPAL'
  AND p.permission_code IN ('device_area', 'device_area:read');

-- MAINTENANCE：全部区域权限
INSERT INTO `role_permission` (role_id, permission_id)
SELECT r.id, p.id FROM `role` r, `permission` p
WHERE r.role_code = 'MAINTENANCE'
  AND p.permission_code IN (
    'device_area', 'device_area:read', 'device_area:create',
    'device_area:update', 'device_area:delete'
  );
