package com.experiment.smartlightingexp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.experiment.smartlightingexp.entity.ControlCommand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 控制指令 Mapper — 提供控制指令表的 CRUD 操作。
 */
@Mapper
public interface ControlCommandMapper extends BaseMapper<ControlCommand> {

    /**
     * 查询指定设备在指定时间范围内的控制指令（按时间升序）。
     */
    @Select("SELECT * FROM control_command " +
            "WHERE device_id = #{deviceId} " +
            "  AND issued_at BETWEEN #{start} AND #{end} " +
            "ORDER BY issued_at ASC")
    List<ControlCommand> selectByDeviceAndTimeRange(String deviceId, LocalDateTime start, LocalDateTime end);
}
