package com.experiment.smartlightingexp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.experiment.smartlightingexp.entity.Permission;
import com.experiment.smartlightingexp.mapper.PermissionMapper;
import com.experiment.smartlightingexp.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 权限 Service 实现 — 权限管理的业务逻辑。
 */
@Service
@RequiredArgsConstructor
public class PermissionServiceImpl extends ServiceImpl<PermissionMapper, Permission> implements PermissionService {

    private final PermissionMapper permissionMapper;

    @Override
    public List<String> getPermissionCodesByRoleId(Long roleId) {
        return permissionMapper.selectPermissionCodesByRoleId(roleId);
    }

    @Override
    public List<String> getAllPermissionCodes() {
        return permissionMapper.selectAllPermissionCodes();
    }

    @Override
    public List<String> getPermissionCodesByRoleCode(String roleCode) {
        return permissionMapper.selectPermissionCodesByRoleCode(roleCode);
    }
}
