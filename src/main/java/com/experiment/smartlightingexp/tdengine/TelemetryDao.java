package com.experiment.smartlightingexp.tdengine;

import com.experiment.smartlightingexp.entity.Telemetry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class TelemetryDao {

    private final TdengineTemplate tpl;

    private static final RowMapper<Telemetry> ROW_MAPPER = (rs, rowNum) -> {
        Telemetry t = new Telemetry();
        Timestamp ts = rs.getTimestamp("ts");
        if (ts != null) t.setCollectedAt(ts.toLocalDateTime());
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
        return tpl.query(sql, ROW_MAPPER, deviceId, Timestamp.valueOf(LocalDateTime.now().minusHours(24)));
    }

    public Telemetry latest(String deviceId) {
        String sql = "SELECT ts, device_id, illuminance, temperature, humidity, pm25, aqi, pir, traffic_flow FROM telemetry"
                + " WHERE device_id = ? ORDER BY ts DESC LIMIT 1";
        return tpl.queryForObject(sql, ROW_MAPPER, deviceId);
    }

    private void appendWhere(StringBuilder sql, List<Object> params, String deviceId, LocalDateTime start, LocalDateTime end) {
        List<String> conds = new ArrayList<>();
        if (deviceId != null && !deviceId.isBlank()) {
            conds.add("device_id = ?");
            params.add(deviceId);
        }
        if (start != null) {
            conds.add("ts >= ?");
            params.add(Timestamp.valueOf(start));
        }
        if (end != null) {
            conds.add("ts <= ?");
            params.add(Timestamp.valueOf(end));
        }
        if (!conds.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conds));
        }
    }
}
