package com.experiment.smartlightingexp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.experiment.smartlightingexp.entity.User;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户 Mapper — 提供用户表的 CRUD 操作。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Delete("DELETE FROM `user` WHERE id = #{id}")
    int physicalDeleteById(@Param("id") Long id);
}
