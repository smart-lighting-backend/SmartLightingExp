package com.experiment.smartlightingexp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.experiment.smartlightingexp.entity.Role;

import java.util.List;

/**
 * 角色 Service — 角色管理的业务逻辑接口。
 */
public interface RoleService extends IService<Role> {

    /**
     * 根据角色编码查询角色。
     */
    Role getByRoleCode(String roleCode);

    /**
     * 查询角色拥有的权限编码列表。
     */
    List<String> getPermissionCodesByRoleId(Long roleId);

    /**
     * 为角色重新分配权限（先清空旧权限，再插入新权限）。
     */
    void assignPermissions(Long roleId, List<Long> permissionIds);
}
