package com.experiment.smartlightingexp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.experiment.smartlightingexp.entity.AlarmRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 告警记录 Mapper — 提供告警记录表的 CRUD 操作。
 */
@Mapper
public interface AlarmRecordMapper extends BaseMapper<AlarmRecord> {
}
