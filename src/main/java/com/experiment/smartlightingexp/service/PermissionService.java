package com.experiment.smartlightingexp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.experiment.smartlightingexp.entity.Permission;

import java.util.List;

/**
 * 权限 Service — 权限管理的业务逻辑接口。
 */
public interface PermissionService extends IService<Permission> {

    /**
     * 根据角色ID查询该角色拥有的权限编码列表。
     */
    List<String> getPermissionCodesByRoleId(Long roleId);
}
