package com.experiment.smartlightingexp.service;

import com.experiment.smartlightingexp.common.BusinessException;
import com.experiment.smartlightingexp.config.MaxKbProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class MaxKbClient {

    private final MaxKbProperties properties;
    private final ObjectMapper objectMapper;

    private RestClient restClient;

    @PostConstruct
    void init() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /** 兼容旧调用：纯用户消息，无 system prompt */
    public String chat(String question) {
        return chatInternal(List.of(
                Map.of("role", "user", "content", question)
        ));
    }

    /** 带 system prompt 的调用，返回原始文本（可能包含 JSON） */
    public String chatWithSystem(String systemPrompt, String userMessage) {
        return chatInternal(List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)
        ));
    }

    private String chatInternal(List<Map<String, String>> messages) {
        if (!properties.isConfigured()) {
            throw new BusinessException(500, "MaxKB 未配置，请设置 MAXKB_CHAT_COMPLETIONS_URL 和 MAXKB_API_KEY");
        }

        try {
            String requestJson = objectMapper.writeValueAsString(Map.of(
                    "model", properties.getModel(),
                    "stream", false,
                    "messages", messages
            ));

            String responseJson = restClient
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
            // 调试：检查知识库引用（references 有值说明检索成功，空/null 说明未命中知识库）
            Object refs = response.get("references");
            int refCount = refs instanceof List<?> list ? list.size() : 0;
            log.info("MaxKB 知识库引用数={}, content长度={}",
                    refCount, extractContent(response).length());
            return extractContent(response);
        } catch (RestClientException e) {
            log.error("MaxKB 请求失败: {}", e.getMessage());
            throw new BusinessException(502, "MaxKB 问答服务调用失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("MaxKB 处理异常", e);
            throw new BusinessException(502, "MaxKB 服务异常: " + e.getMessage());
        }
    }

    // ── JSON 提取 ────────────────────────────────────────────────────────────

    private static final Pattern JSON_BLOCK = Pattern.compile(
            "```(?:json)?\\s*([\\s\\S]*?)```");
    private static final Pattern JSON_OBJECT = Pattern.compile(
            "\\{[^{}]*(?:\\{[^{}]*}[^{}]*)*}");

    /**
     * 从 MaxKB 返回的文本中尝试提取 JSON 对象。
     * 优先匹配 ```json ... ``` 代码块，其次匹配裸 JSON 对象。
     */
    public Map<String, Object> tryExtractJson(String raw) {
        if (raw == null || raw.isBlank()) return null;

        // 1. 尝试整体解析（理想情况：MaxKB 只返回了纯 JSON）
        Map<String, Object> parsed = tryParse(raw.trim());
        if (parsed != null) return parsed;

        // 2. 匹配 ```json ... ``` 代码块
        Matcher block = JSON_BLOCK.matcher(raw);
        while (block.find()) {
            parsed = tryParse(block.group(1).trim());
            if (parsed != null) return parsed;
        }

        // 3. 在文本中搜索 JSON 对象
        Matcher obj = JSON_OBJECT.matcher(raw);
        while (obj.find()) {
            parsed = tryParse(obj.group());
            if (parsed != null && parsed.containsKey("intent")) return parsed;
        }

        return null;
    }

    private Map<String, Object> tryParse(String candidate) {
        try {
            return objectMapper.readValue(candidate,
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    // ── 内容提取 ────────────────────────────────────────────────────────────

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
