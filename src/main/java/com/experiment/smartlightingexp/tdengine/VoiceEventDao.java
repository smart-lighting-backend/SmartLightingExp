package com.experiment.smartlightingexp.tdengine;

import com.experiment.smartlightingexp.entity.VoiceEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class VoiceEventDao {

    private final TdengineTemplate tpl;

    private static final RowMapper<VoiceEvent> ROW_MAPPER = (rs, rowNum) -> {
        VoiceEvent e = new VoiceEvent();
        Timestamp ts = rs.getTimestamp("ts");
        if (ts != null) e.setOccurredAt(ts.toLocalDateTime());
        e.setDeviceId(rs.getString("device_id"));
        e.setType(rs.getString("type"));
        e.setContent(rs.getString("content"));
        e.setSource(rs.getString("source"));
        return e;
    };

    public List<VoiceEvent> queryPage(String deviceId, String type, String source, int page, int size) {
        StringBuilder sql = new StringBuilder(
                "SELECT ts, device_id, type, content, source FROM voice_event");
        List<Object> params = new ArrayList<>();
        appendWhere(sql, params, deviceId, type, source);
        sql.append(" ORDER BY ts DESC LIMIT ? OFFSET ?");
        params.add(size);
        params.add((page - 1) * size);
        return tpl.query(sql.toString(), ROW_MAPPER, params.toArray());
    }

    public long countPage(String deviceId, String type, String source) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM voice_event");
        List<Object> params = new ArrayList<>();
        appendWhere(sql, params, deviceId, type, source);
        return tpl.count(sql.toString(), params.toArray());
    }

    public VoiceEvent latest(String deviceId) {
        String sql = "SELECT ts, device_id, type, content, source FROM voice_event"
                + " WHERE device_id = ? ORDER BY ts DESC LIMIT 1";
        return tpl.queryForObject(sql, ROW_MAPPER, deviceId);
    }

    private void appendWhere(StringBuilder sql, List<Object> params, String deviceId, String type, String source) {
        List<String> conds = new ArrayList<>();
        if (deviceId != null && !deviceId.isBlank()) {
            conds.add("device_id = ?");
            params.add(deviceId);
        }
        if (type != null && !type.isBlank()) {
            conds.add("type = ?");
            params.add(type);
        }
        if (source != null && !source.isBlank()) {
            conds.add("source = ?");
            params.add(source);
        }
        if (!conds.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conds));
        }
    }
}
