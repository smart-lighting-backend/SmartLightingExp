package com.experiment.smartlightingexp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.experiment.smartlightingexp.entity.LightingPolicy;
import org.apache.ibatis.annotations.Mapper;

/**
 * 照明策略 Mapper — 提供照明策略表的 CRUD 操作。
 */
@Mapper
public interface LightingPolicyMapper extends BaseMapper<LightingPolicy> {
}
