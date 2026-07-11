package com.experiment.smartlightingexp.dto;

import java.util.List;
import lombok.Data;

/**
 * 用户查询请求 DTO — 支持按角色、用户名等多条件组合筛选 + 分页。
 */
@Data
public class UserQueryRequest {

    /** 角色ID（精确匹配） */
    private Long roleId;

    /** 用户名（模糊匹配） */
    private String username;

    /** 真实姓名（模糊匹配） */
    private String realName;

    /** 手机号（模糊匹配） */
    private String phone;

    /** 邮箱（模糊匹配） */
    private String email;

    /** 管辖区域编码（精确匹配） */
    private String areaCode;

    /** 是否启用 */
    private Boolean enabled;

    /** 用户ID 列表（用于按选中的 ID 导出） */
    private List<Long> ids;

    // ======================== 分页参数 ========================

    /** 当前页码，默认 1 */
    private int page = 1;

    /** 每页条数，默认 20 */
    private int size = 20;
}
