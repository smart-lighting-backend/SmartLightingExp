package com.experiment.smartlightingexp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.experiment.smartlightingexp.entity.User;

/**
 * 用户 Service — 用户管理的业务逻辑接口。
 */
public interface UserService extends IService<User> {

    /**
     * 根据用户名查询用户。
     */
    User getByUsername(String username);
}
