package com.experiment.smartlightingexp.tdengine;

import com.experiment.smartlightingexp.entity.Telemetry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class TelemetryDao {

    private final TdengineTemplate tpl;

    /* TDengine 存储 UTC, 前端传入/显示均为北京时间(+8) */
    private static final ZoneId ZONE_BEIJING = ZoneId.of("Asia/Shanghai");
    private static final ZoneId ZONE_UTC     = ZoneId.of("UTC");
    private static final DateTimeFormatter UTC_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZONE_UTC);

    private static final RowMapper<Telemetry> ROW_MAPPER = (rs, rowNum) -> {
        Telemetry t = new Telemetry();
        Timestamp ts = rs.getTimestamp("ts");
        /* JDBC 驱动已正确解析 TDengine REST 返回的 UTC 时间戳,
           toLocalDateTime() 自动转为 JVM 时区 (Asia/Shanghai = 北京时间) */
        if (ts != null) {
            t.setCollectedAt(ts.toLocalDateTime());
        }
        t.setDeviceId(rs.getString("device_id"));
        t.setIlluminance(rs.getBigDecimal("illuminance"));
        t.setTemperature(rs.getBigDecimal("temperature"));
        t.setHumidity(rs.getBigDecimal("humidity"));
        t.setPm25(rs.getBigDecimal("pm25"));
        t.setAqi(rs.getObject("aqi") != null ? rs.getInt("aqi") : null);
        t.setPir(rs.getObject("pir") != null ? rs.getInt("pir") : null);
        t.setTrafficFlow(rs.getObject("traffic_flow") != null ? rs.getInt("traffic_flow") : null);
        return t;
    };

    public List<Telemetry> queryHistory(String deviceId, LocalDateTime start, LocalDateTime end, int page, int size) {
        StringBuilder sql = new StringBuilder(
                "SELECT ts, device_id, illuminance, temperature, humidity, pm25, aqi, pir, traffic_flow FROM telemetry");
        List<Object> params = new ArrayList<>();
        appendWhere(sql, params, deviceId, start, end);
        sql.append(" ORDER BY ts DESC LIMIT ? OFFSET ?");
        params.add(size);
        params.add((page - 1) * size);
        return tpl.query(sql.toString(), ROW_MAPPER, params.toArray());
    }

    public long countHistory(String deviceId, LocalDateTime start, LocalDateTime end) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM telemetry");
        List<Object> params = new ArrayList<>();
        appendWhere(sql, params, deviceId, start, end);
        return tpl.count(sql.toString(), params.toArray());
    }

    public List<Telemetry> query24h(String deviceId) {
        String sql = "SELECT ts, device_id, illuminance, temperature, humidity, pm25, aqi, pir, traffic_flow FROM telemetry"
                + " WHERE device_id = ? AND ts >= ? ORDER BY ts ASC";
        /* 24 小时前, 北京时间 → UTC 字符串 */
        String utc24h = ZonedDateTime.now(ZONE_BEIJING).minusHours(24)
                .withZoneSameInstant(ZONE_UTC).format(UTC_FMT);
        return tpl.query(sql, ROW_MAPPER, deviceId, utc24h);
    }

    public Telemetry latest(String deviceId) {
        String sql = "SELECT ts, device_id, illuminance, temperature, humidity, pm25, aqi, pir, traffic_flow FROM telemetry"
                + " WHERE device_id = ? ORDER BY ts DESC LIMIT 1";
        return tpl.queryForObject(sql, ROW_MAPPER, deviceId);
    }

    /**
     * 将北京时间转为 UTC 字符串后查询 TDengine。
     * 直接传字符串, 绕过 Timestamp.toString() 的 JVM 时区转换问题,
     * 确保 TDengine 字符串比较结果正确。
     */
    private void appendWhere(StringBuilder sql, List<Object> params,
                             String deviceId, LocalDateTime start, LocalDateTime end) {
        List<String> conds = new ArrayList<>();
        if (deviceId != null && !deviceId.isBlank()) {
            conds.add("device_id = ?");
            params.add(deviceId);
        }
        if (start != null) {
            conds.add("ts >= ?");
            params.add(start.atZone(ZONE_BEIJING).withZoneSameInstant(ZONE_UTC).format(UTC_FMT));
        }
        if (end != null) {
            conds.add("ts <= ?");
            params.add(end.atZone(ZONE_BEIJING).withZoneSameInstant(ZONE_UTC).format(UTC_FMT));
        }
        if (!conds.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conds));
        }
    }
}
