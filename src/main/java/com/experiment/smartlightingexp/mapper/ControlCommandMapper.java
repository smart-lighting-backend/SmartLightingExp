package com.experiment.smartlightingexp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.experiment.smartlightingexp.entity.ControlCommand;
import org.apache.ibatis.annotations.Mapper;

/**
 * 控制指令 Mapper — 提供控制指令表的 CRUD 操作。
 */
@Mapper
public interface ControlCommandMapper extends BaseMapper<ControlCommand> {
}
