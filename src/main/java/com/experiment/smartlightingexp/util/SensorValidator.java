package com.experiment.smartlightingexp.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 传感器数据范围校验工具。
 * 与 HealthScoreTask 中的 checkRange 逻辑保持一致，供遥测实时检测复用。
 */
public class SensorValidator {

    private SensorValidator() {}

    /** 字段名 → [min, max] */
    private static final Map<String, double[]> RANGES = new LinkedHashMap<>();
    static {
        RANGES.put("illuminance", new double[]{0, 2000});
        RANGES.put("temperature", new double[]{-10, 50});
        RANGES.put("humidity",    new double[]{0, 100});
        RANGES.put("pm25",        new double[]{0, 500});
        RANGES.put("aqi",         new double[]{0, 500});
    }

    /**
     * 校验遥测数据中各传感器字段是否在合理范围内。
     *
     * @param fieldValues 传感器字段名 → 数值
     * @return 异常字段列表，每个元素为 [字段名, 当前值, 范围描述]
     */
    public static List<AbnormalField> validate(Map<String, Object> fieldValues) {
        List<AbnormalField> abnormal = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : RANGES.entrySet()) {
            String key = entry.getKey();
            double[] range = entry.getValue();
            Object val = fieldValues.get(key);
            if (val == null) continue;
            try {
                double d = Double.parseDouble(val.toString());
                if (d < range[0] || d > range[1]) {
                    abnormal.add(new AbnormalField(key, d, range[0], range[1]));
                }
            } catch (NumberFormatException e) {
                abnormal.add(new AbnormalField(key, Double.NaN, range[0], range[1]));
            }
        }
        return abnormal;
    }

    /**
     * 从 Telemetry 对象提取各传感器字段值，用于校验。
     */
    public static Map<String, Object> extractFieldValues(Object telemetry) {
        Map<String, Object> map = new LinkedHashMap<>();
        try {
            for (java.lang.reflect.Field f : telemetry.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(telemetry);
                if (val != null) {
                    map.put(f.getName(), val);
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    public static class AbnormalField {
        public final String field;
        public final double value;
        public final double min;
        public final double max;

        AbnormalField(String field, double value, double min, double max) {
            this.field = field;
            this.value = value;
            this.min = min;
            this.max = max;
        }

        public String description() {
            if (Double.isNaN(value)) {
                return field + " 值无法解析，期望范围 [" + min + ", " + max + "]";
            }
            return field + "=" + value + "，超出 [" + min + ", " + max + "]";
        }

        @Override
        public String toString() {
            return description();
        }
    }
}
