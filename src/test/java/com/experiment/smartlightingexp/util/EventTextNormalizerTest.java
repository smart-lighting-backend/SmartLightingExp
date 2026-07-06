package com.experiment.smartlightingexp.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventTextNormalizerTest {

    @Test
    void repairsVisionEventTypesDecodedWithGbk() {
        assertEquals("行人检测", EventTextNormalizer.normalize(gbkMojibake("行人检测")));
        assertEquals("车辆通行", EventTextNormalizer.normalize(gbkMojibake("车辆通行")));
        assertEquals("异常停车", EventTextNormalizer.normalize(gbkMojibake("异常停车")));
        assertEquals("危险场景", EventTextNormalizer.normalize(gbkMojibake("危险场景")));
    }

    @Test
    void repairsVoiceEventFieldsDecodedWithGbk() {
        assertEquals("广播", EventTextNormalizer.normalize(gbkMojibake("广播")));
        assertEquals("警告", EventTextNormalizer.normalize(gbkMojibake("警告")));
        assertEquals("自动", EventTextNormalizer.normalize(gbkMojibake("自动")));
        assertEquals(
                "设备自检完成，所有模块运行正常",
                EventTextNormalizer.normalize(gbkMojibake("设备自检完成，所有模块运行正常")));
        assertEquals(
                "雨雾天气预警，请减速慢行，开启雾灯",
                EventTextNormalizer.normalize(gbkMojibake("雨雾天气预警，请减速慢行，开启雾灯")));
    }

    @Test
    void leavesNormalTextUntouched() {
        assertEquals("播报", EventTextNormalizer.normalize("播报"));
        assertEquals("道路施工区域，请注意避让", EventTextNormalizer.normalize("道路施工区域，请注意避让"));
    }

    @Test
    void queryValuesIncludeLegacyVariantsForFiltering() {
        assertTrue(EventTextNormalizer.queryValues("广播").contains("广播"));
        assertTrue(EventTextNormalizer.queryValues("广播").contains(gbkMojibake("广播")));
    }

    private static String gbkMojibake(String value) {
        return new String(value.getBytes(StandardCharsets.UTF_8), Charset.forName("GBK"));
    }
}
