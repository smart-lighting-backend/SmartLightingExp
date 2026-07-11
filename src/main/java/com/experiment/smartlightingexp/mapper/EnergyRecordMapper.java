package com.experiment.smartlightingexp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.experiment.smartlightingexp.entity.EnergyRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface EnergyRecordMapper extends BaseMapper<EnergyRecord> {

    @Select("<script>" +
            "SELECT * FROM energy_record " +
            "WHERE device_id IN <foreach item='id' collection='deviceIds' open='(' separator=',' close=')'>#{id}</foreach> " +
            "  AND record_date = #{recordDate}" +
            "</script>")
    List<EnergyRecord> selectByDeviceIdsAndDate(@Param("deviceIds") List<String> deviceIds,
                                                 @Param("recordDate") LocalDate recordDate);
}
