package com.experiment.smartlightingexp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录响应结果。
 */
@Data
@AllArgsConstructor
public class LoginResponse {

    private String token;
    private String username;
    private String roleCode;
}
