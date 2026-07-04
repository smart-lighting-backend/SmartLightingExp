package com.experiment.smartlightingexp.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限树节点 — 用于前端权限分配树形选择器。
 */
@Data
public class PermissionTreeNode {

    private Long id;
    private Long parentId;
    private String name;
    private String permissionCode;
    private String type;     // MODULE / ACTION
    private String description;
    private boolean checked; // 该角色是否已拥有此权限

    private List<PermissionTreeNode> children = new ArrayList<>();
}
