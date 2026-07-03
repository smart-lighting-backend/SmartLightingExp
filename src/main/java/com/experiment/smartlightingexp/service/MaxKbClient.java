package com.experiment.smartlightingexp.service;

import com.experiment.smartlightingexp.common.BusinessException;
import com.experiment.smartlightingexp.config.MaxKbProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MaxKbClient {

    private final MaxKbProperties properties;
    private final RestClient restClient = RestClient.create();

    public String chat(String question) {
        if (!properties.isConfigured()) {
            throw new BusinessException(500, "MaxKB 未配置，请设置 MAXKB_CHAT_COMPLETIONS_URL 和 MAXKB_API_KEY");
        }

        Map<String, Object> request = Map.of(
                "model", properties.getModel(),
                "stream", false,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", question
                ))
        );

        try {
            Map<String, Object> response = restClient.post()
                    .uri(properties.getChatCompletionsUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return extractContent(response);
        } catch (RestClientException e) {
            throw new BusinessException(502, "MaxKB 问答服务调用失败: " + e.getMessage());
        }
    }

    private String extractContent(Map<String, Object> response) {
        if (response == null) {
            throw new BusinessException(502, "MaxKB 返回为空");
        }
        Object choicesValue = response.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()) {
            throw new BusinessException(502, "MaxKB 返回缺少 choices");
        }
        Object firstChoice = choices.get(0);
        if (!(firstChoice instanceof Map<?, ?> choice)) {
            throw new BusinessException(502, "MaxKB 返回格式不正确");
        }

        Object messageValue = choice.get("message");
        if (messageValue instanceof Map<?, ?> message) {
            Object content = message.get("content");
            if (content != null) {
                return content.toString();
            }
        }

        Object text = choice.get("text");
        if (text != null) {
            return text.toString();
        }
        throw new BusinessException(502, "MaxKB 返回缺少回答内容");
    }
}
