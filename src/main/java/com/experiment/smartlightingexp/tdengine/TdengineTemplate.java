package com.experiment.smartlightingexp.tdengine;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TdengineTemplate {

    private final JdbcTemplate jdbc;

    public TdengineTemplate(@Qualifier("tdengineJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        return jdbc.query(sql, mapper, params);
    }

    public <T> T queryForObject(String sql, RowMapper<T> mapper, Object... params) {
        List<T> list = query(sql, mapper, params);
        return list.isEmpty() ? null : list.get(0);
    }

    public long count(String sql, Object... params) {
        Long result = jdbc.queryForObject(sql, Long.class, params);
        return result != null ? result : 0;
    }

    public void execute(String sql) {
        jdbc.update(sql);
    }
}
