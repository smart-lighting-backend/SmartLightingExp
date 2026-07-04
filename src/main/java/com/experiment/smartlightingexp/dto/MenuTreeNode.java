package com.experiment.smartlightingexp.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单树节点 — 用于前端动态导航渲染。
 */
@Data
public class MenuTreeNode {

    private Long id;
    private Long parentId;
    private String name;
    private String permissionCode;
    private String icon;
    private String path;
    private String component;
    private Integer sort;
    private Boolean enabled;

    private List<MenuTreeNode> children = new ArrayList<>();
}
