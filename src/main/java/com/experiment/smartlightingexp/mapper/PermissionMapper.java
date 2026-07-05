package com.experiment.smartlightingexp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.experiment.smartlightingexp.entity.Permission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 权限 Mapper — 提供权限表的 CRUD 操作。
 */
@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {

    /**
     * 根据角色ID查询该角色拥有的所有权限编码。
     */
    @Select("SELECT p.permission_code FROM permission p " +
            "INNER JOIN role_permission rp ON p.id = rp.permission_id " +
            "WHERE rp.role_id = #{roleId}")
    List<String> selectPermissionCodesByRoleId(Long roleId);

    /**
     * 根据角色编码查询该角色拥有的所有权限编码（动态查询，Token 中的已是旧数据）。
     */
    @Select("SELECT p.permission_code FROM permission p " +
            "INNER JOIN role_permission rp ON p.id = rp.permission_id " +
            "INNER JOIN `role` r ON r.id = rp.role_id " +
            "WHERE r.role_code = #{roleCode}")
    List<String> selectPermissionCodesByRoleCode(String roleCode);
}
