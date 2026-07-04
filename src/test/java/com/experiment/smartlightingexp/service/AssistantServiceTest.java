package com.experiment.smartlightingexp.service;

import com.experiment.smartlightingexp.dto.AssistantChatResponse;
import com.experiment.smartlightingexp.entity.LightingPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantServiceTest {

    private final MaxKbClient maxKbClient = mock(MaxKbClient.class);
    private final LightingPolicyService lightingPolicyService = mock(LightingPolicyService.class);
    private final AssistantService assistantService = new AssistantService(
            maxKbClient, lightingPolicyService, new ObjectMapper());

    @Test
    void thresholdCommandUpdatesPolicyWithoutCallingMaxKb() {
        LightingPolicy policy = new LightingPolicy();
        policy.setId(1L);
        policy.setName("光照低于阈值自动开灯");
        policy.setPolicyType("THRESHOLD");
        policy.setConditions("{\"lux_lt\":50,\"lux_gt\":200}");
        policy.setPriority(1);
        policy.setDeleted(false);
        when(lightingPolicyService.list()).thenReturn(List.of(policy));

        AssistantChatResponse response = assistantService.chat("把阈值调到30");

        assertThat(response.getType()).isEqualTo("THRESHOLD_UPDATED");
        assertThat(response.getContent()).contains("30 lux");
        assertThat(response.getAction()).containsEntry("policyId", 1L);
        assertThat(policy.getConditions()).contains("\"lux_lt\":30");
        assertThat(policy.getConditions()).contains("\"lux_gt\":200");
        verify(lightingPolicyService).updateById(policy);
        verify(maxKbClient, never()).chat(any());
    }

    @Test
    void maintenanceQuestionUsesMaxKb() {
        when(maxKbClient.chat("灯不亮怎么办")).thenReturn("请先查看设备是否在线。");

        AssistantChatResponse response = assistantService.chat("灯不亮怎么办");

        assertThat(response.getType()).isEqualTo("KNOWLEDGE_QA");
        assertThat(response.getContent()).isEqualTo("请先查看设备是否在线。");
        verify(maxKbClient).chat("灯不亮怎么办");
        verify(lightingPolicyService, never()).updateById(any());
    }
}
