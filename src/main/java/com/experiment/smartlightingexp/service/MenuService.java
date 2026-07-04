package com.experiment.smartlightingexp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.experiment.smartlightingexp.dto.MenuTreeNode;
import com.experiment.smartlightingexp.entity.Menu;

import java.util.List;

/**
 * 菜单 Service 接口。
 */
public interface MenuService extends IService<Menu> {

    /**
     * 获取完整菜单树（后台管理用）。
     */
    List<MenuTreeNode> getMenuTree();

    /**
     * 根据权限编码列表获取用户可见的菜单树（前端导航用）。
     */
    List<MenuTreeNode> getVisibleMenuTree(List<String> permissionCodes);
}
