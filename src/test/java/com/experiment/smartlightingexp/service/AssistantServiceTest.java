package com.experiment.smartlightingexp.service;

import com.experiment.smartlightingexp.dto.AssistantChatResponse;
import com.experiment.smartlightingexp.entity.LightingPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.experiment.smartlightingexp.service.DeviceService;
import com.experiment.smartlightingexp.service.AlarmRecordService;
import com.experiment.smartlightingexp.mapper.ControlCommandMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantServiceTest {

    private final MaxKbClient maxKbClient = mock(MaxKbClient.class);
    private final LightingPolicyService lightingPolicyService = mock(LightingPolicyService.class);
    private final DeviceService deviceService = mock(DeviceService.class);
    private final AlarmRecordService alarmRecordService = mock(AlarmRecordService.class);
    private final ControlCommandMapper controlCommandMapper = mock(ControlCommandMapper.class);
    private final AssistantService assistantService = new AssistantService(
            maxKbClient, lightingPolicyService, new ObjectMapper(), deviceService, alarmRecordService, controlCommandMapper);

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
        verify(maxKbClient, never()).chatWithSystem(any(), any());
    }

    @Test
    void maintenanceQuestionUsesMaxKb() {
        // 纯知识问答不含修改关键词，走轻量 System Prompt 路径
        when(maxKbClient.chatWithSystem(any(), any())).thenReturn("请先查看设备是否在线。");

        AssistantChatResponse response = assistantService.chat("灯不亮怎么办");

        assertThat(response.getType()).isEqualTo("KNOWLEDGE_QA");
        assertThat(response.getContent()).isEqualTo("请先查看设备是否在线。");
        verify(lightingPolicyService, never()).updateById(any());
    }

    @Test
    void aiIntentUpdatePolicy() {
        LightingPolicy policy = new LightingPolicy();
        policy.setId(2L);
        policy.setName("深夜节能调光");
        policy.setPolicyType("SCENE");
        policy.setConditions("{\"lux_lt\":30,\"temp_lt\":5,\"startTime\":\"23:00\",\"endTime\":\"05:00\"}");
        policy.setAction("DIMMING(50)");
        policy.setPriority(10);
        policy.setEnabled(true);
        policy.setDeleted(false);
        when(lightingPolicyService.list()).thenReturn(List.of(policy));

        String aiResponse = "好的，已将亮度调到60%。\n```json\n{\"intent\":\"UPDATE_POLICY\",\"params\":{\"brightness\":60}}\n```";
        when(maxKbClient.chatWithSystem(any(), any())).thenReturn(aiResponse);

        Map<String, Object> intentJson = Map.of(
                "intent", "UPDATE_POLICY",
                "params", Map.of("brightness", 60));
        when(maxKbClient.tryExtractJson(aiResponse)).thenReturn(intentJson);

        AssistantChatResponse response = assistantService.chat("把亮度调到60%");

        assertThat(response.getType()).isEqualTo("THRESHOLD_UPDATED");
        assertThat(response.getContent()).contains("亮度");
        assertThat(response.getAction()).containsEntry("name", "AI_UPDATE_POLICY");
        assertThat(response.getAction()).containsEntry("policyId", 2L);
        verify(lightingPolicyService).updateById(any());
    }
}
