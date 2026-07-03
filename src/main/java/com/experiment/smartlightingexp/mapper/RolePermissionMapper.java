package com.experiment.smartlightingexp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.experiment.smartlightingexp.entity.RolePermission;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色-权限关联 Mapper。
 */
@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermission> {
}
