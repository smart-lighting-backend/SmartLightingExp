package com.experiment.smartlightingexp.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoginResponseTest {

    @Test
    void shouldExposeCompleteUserProfile() {
        LoginResponse response = new LoginResponse(
                "token", "admin", "系统管理员", "技术部", "13800000001",
                "SUPER_ADMIN", "超级管理员", List.of("user:read"), List.of());

        assertEquals("系统管理员", response.getRealName());
        assertEquals("技术部", response.getDepartment());
        assertEquals("13800000001", response.getPhone());
        assertEquals("SUPER_ADMIN", response.getRoleCode());
    }
}
