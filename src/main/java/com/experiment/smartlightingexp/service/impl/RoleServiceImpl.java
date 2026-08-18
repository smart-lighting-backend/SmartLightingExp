package com.experiment.smartlightingexp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.experiment.smartlightingexp.entity.Role;
import com.experiment.smartlightingexp.entity.RolePermission;
import com.experiment.smartlightingexp.mapper.PermissionMapper;
import com.experiment.smartlightingexp.mapper.RoleMapper;
import com.experiment.smartlightingexp.mapper.RolePermissionMapper;
import com.experiment.smartlightingexp.service.RoleService;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 角色 Service 实现 — 角色 CRUD 和权限分配。
 */
@Service
public class RoleServiceImpl extends ServiceImpl<RoleMapper, Role> implements RoleService {

    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final Cache<String, List<String>> permissionCache;

    public RoleServiceImpl(PermissionMapper permissionMapper,
                           RolePermissionMapper rolePermissionMapper,
                           @Qualifier("permissionCache") Cache<String, List<String>> permissionCache) {
        this.permissionMapper = permissionMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionCache = permissionCache;
    }

    @Override
    public Role getByRoleCode(String roleCode) {
        return lambdaQuery().eq(Role::getRoleCode, roleCode).one();
    }

    @Override
    public List<String> getPermissionCodesByRoleId(Long roleId) {
        return permissionMapper.selectPermissionCodesByRoleId(roleId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        // 清空旧权限
        rolePermissionMapper.delete(
                new LambdaQueryWrapper<RolePermission>()
                        .eq(RolePermission::getRoleId, roleId));

        // 插入新权限
        if (permissionIds != null && !permissionIds.isEmpty()) {
            List<RolePermission> list = permissionIds.stream()
                    .map(permId -> {
                        RolePermission rp = new RolePermission();
                        rp.setRoleId(roleId);
                        rp.setPermissionId(permId);
                        return rp;
                    }).collect(Collectors.toList());
            list.forEach(rolePermissionMapper::insert);
        }

        // 权限变更后清除缓存，确保下一次请求即时生效
        Role role = getById(roleId);
        if (role != null) {
            permissionCache.invalidate(role.getRoleCode());
        }
    }
}
