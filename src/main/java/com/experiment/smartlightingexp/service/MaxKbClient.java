package com.experiment.smartlightingexp.service;

import com.experiment.smartlightingexp.common.BusinessException;
import com.experiment.smartlightingexp.config.MaxKbProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MaxKbClient {

    private final MaxKbProperties properties;
    private final ObjectMapper objectMapper;

    public String chat(String question) {
        if (!properties.isConfigured()) {
            throw new BusinessException(500, "MaxKB 未配置，请设置 MAXKB_CHAT_COMPLETIONS_URL 和 MAXKB_API_KEY");
        }

        try {
            // 手动序列化 JSON，确保 UTF-8 编码正确
            String requestJson = objectMapper.writeValueAsString(Map.of(
                    "model", properties.getModel(),
                    "stream", false,
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", question
                    ))
            ));

            String responseJson = RestClient.create()
                    .post()
                    .uri(properties.getChatCompletionsUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .body(requestJson)
                    .retrieve()
                    .body(String.class);

            if (responseJson == null || responseJson.isBlank()) {
                throw new BusinessException(502, "MaxKB 返回为空");
            }

            Map<String, Object> response = objectMapper.readValue(responseJson,
                    new TypeReference<Map<String, Object>>() {});
            return extractContent(response);
        } catch (RestClientException e) {
            log.error("MaxKB 请求失败: {}", e.getMessage());
            throw new BusinessException(502, "MaxKB 问答服务调用失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("MaxKB 处理异常", e);
            throw new BusinessException(502, "MaxKB 服务异常: " + e.getMessage());
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
