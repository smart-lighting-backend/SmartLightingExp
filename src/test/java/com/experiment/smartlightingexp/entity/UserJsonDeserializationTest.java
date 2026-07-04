package com.experiment.smartlightingexp.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserJsonDeserializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ignoresDisplayOnlyRoleFieldsWhenUpdatingUser() throws Exception {
        String json = """
                {
                  "id": 3,
                  "username": "maintenance",
                  "realName": "maintenance-user",
                  "roleId": 2,
                  "roleName": "Maintenance",
                  "roleCode": "MAINTENANCE",
                  "enabled": true
                }
                """;

        User user = objectMapper.readValue(json, User.class);

        assertThat(user.getId()).isEqualTo(3L);
        assertThat(user.getUsername()).isEqualTo("maintenance");
        assertThat(user.getRoleId()).isEqualTo(2L);
        assertThat(user.getEnabled()).isTrue();
    }
}
