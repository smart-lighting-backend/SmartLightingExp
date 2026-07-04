package com.experiment.smartlightingexp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.experiment.smartlightingexp.dto.MenuTreeNode;
import com.experiment.smartlightingexp.entity.Menu;
import com.experiment.smartlightingexp.mapper.MenuMapper;
import com.experiment.smartlightingexp.service.MenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 菜单 Service 实现。
 */
@Service
@RequiredArgsConstructor
public class MenuServiceImpl extends ServiceImpl<MenuMapper, Menu> implements MenuService {

    private final MenuMapper menuMapper;

    @Override
    public List<MenuTreeNode> getMenuTree() {
        List<Menu> allMenus = menuMapper.selectList(null);
        return buildTree(allMenus, null);
    }

    @Override
    public List<MenuTreeNode> getVisibleMenuTree(List<String> permissionCodes) {
        List<Menu> allMenus = menuMapper.selectList(null);

        // 筛选：无权限要求 或 用户拥有该权限的菜单
        List<Menu> visible = allMenus.stream()
                .filter(m -> m.getEnabled() != null && m.getEnabled())
                .filter(m -> m.getPermissionCode() == null
                        || m.getPermissionCode().isBlank()
                        || permissionCodes.contains(m.getPermissionCode()))
                .collect(Collectors.toList());

        return buildTree(visible, null);
    }

    /**
     * 递归构建菜单树。
     */
    private List<MenuTreeNode> buildTree(List<Menu> menus, Long parentId) {
        List<MenuTreeNode> nodes = new ArrayList<>();
        for (Menu menu : menus) {
            if (!Objects.equals(menu.getParentId(), parentId)) continue;

            MenuTreeNode node = new MenuTreeNode();
            node.setId(menu.getId());
            node.setParentId(menu.getParentId());
            node.setName(menu.getName());
            node.setPermissionCode(menu.getPermissionCode());
            node.setIcon(menu.getIcon());
            node.setPath(menu.getPath());
            node.setComponent(menu.getComponent());
            node.setSort(menu.getSort());
            node.setEnabled(menu.getEnabled());
            node.setChildren(buildTree(menus, menu.getId()));
            nodes.add(node);
        }
        nodes.sort(Comparator.comparingInt(MenuTreeNode::getSort));
        return nodes;
    }
}
