package com.experiment.smartlightingexp.util;

import com.experiment.smartlightingexp.entity.VisionEvent;
import com.experiment.smartlightingexp.entity.VoiceEvent;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Repairs legacy event rows that were written when UTF-8 MQTT payloads were
 * decoded with the platform default charset before persistence.
 */
public final class EventTextNormalizer {

    private static final Charset GBK = Charset.forName("GBK");

    private static final List<String> KNOWN_TEXTS = List.of(
            "行人检测",
            "车辆通行",
            "异常停车",
            "危险场景",
            "警告",
            "播报",
            "广播",
            "预警",
            "自动",
            "手动",
            "系统",
            "请注意，前方路段照明已开启，行人请注意安全",
            "当前区域光照不足，路灯已自动调亮至80%",
            "雨雾天气预警，请减速慢行，开启雾灯",
            "设备自检完成，所有模块运行正常",
            "夜间节能模式已启动，路灯亮度降至30%",
            "道路施工区域，请注意避让",
            "该区域车流量较大，已切换为高峰亮灯模式",
            "空气质量异常，建议减少户外活动"
    );

    private static final Map<String, String> NORMALIZED_TEXTS = buildNormalizedTexts();

    private EventTextNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String normalized = NORMALIZED_TEXTS.get(value);
        if (normalized != null) {
            return normalized;
        }
        return NORMALIZED_TEXTS.getOrDefault(value.trim(), value);
    }

    public static void normalizeVisionEvent(VisionEvent event) {
        if (event == null) {
            return;
        }
        event.setEventType(normalize(event.getEventType()));
    }

    public static void normalizeVoiceEvent(VoiceEvent event) {
        if (event == null) {
            return;
        }
        event.setType(normalize(event.getType()));
        event.setContent(normalize(event.getContent()));
        event.setSource(normalize(event.getSource()));
    }

    public static List<String> queryValues(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        String normalized = normalize(value);
        Set<String> values = new LinkedHashSet<>();
        values.add(normalized);
        values.add(toLegacyVariant(normalized, GBK));
        values.add(toLegacyVariant(normalized, StandardCharsets.ISO_8859_1));
        values.remove(null);
        values.remove("");
        return new ArrayList<>(values);
    }

    private static Map<String, String> buildNormalizedTexts() {
        Map<String, String> map = new HashMap<>();
        for (String text : KNOWN_TEXTS) {
            register(map, text);
        }
        return Collections.unmodifiableMap(map);
    }

    private static void register(Map<String, String> map, String text) {
        map.put(text, text);
        map.put(toLegacyVariant(text, GBK), text);
        map.put(toLegacyVariant(text, StandardCharsets.ISO_8859_1), text);
    }

    private static String toLegacyVariant(String value, Charset wrongCharset) {
        return new String(value.getBytes(StandardCharsets.UTF_8), wrongCharset);
    }
}
