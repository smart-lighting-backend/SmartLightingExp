package com.experiment.smartlightingexp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.experiment.smartlightingexp.entity.Device;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DeviceMapper extends BaseMapper<Device> {

    /** 查全部 device_id（含已软删除），绕过 MyBatis-Plus 全局逻辑删除过滤。用于重名检测。 */
    @Select("SELECT device_id FROM device")
    List<String> selectAllDeviceIdsIncludingDeleted();
}
