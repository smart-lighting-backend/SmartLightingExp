package com.experiment.smartlightingexp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.experiment.smartlightingexp.entity.Role;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色 Mapper — 提供角色表的 CRUD 操作。
 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {
}
