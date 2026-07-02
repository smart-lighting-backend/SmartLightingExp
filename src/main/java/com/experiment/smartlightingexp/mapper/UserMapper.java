package com.experiment.smartlightingexp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.experiment.smartlightingexp.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper — 提供用户表的 CRUD 操作。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
