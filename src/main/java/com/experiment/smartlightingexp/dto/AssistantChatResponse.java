package com.experiment.smartlightingexp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class AssistantChatResponse {

    private String type;

    private String content;

    private Map<String, Object> action;

    public static AssistantChatResponse knowledge(String content) {
        return new AssistantChatResponse("KNOWLEDGE_QA", content, null);
    }

    public static AssistantChatResponse thresholdUpdated(String content, Map<String, Object> action) {
        return new AssistantChatResponse("THRESHOLD_UPDATED", content, action);
    }
}
