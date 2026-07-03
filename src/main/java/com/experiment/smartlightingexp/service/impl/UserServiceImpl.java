package com.experiment.smartlightingexp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.experiment.smartlightingexp.entity.User;
import com.experiment.smartlightingexp.mapper.UserMapper;
import com.experiment.smartlightingexp.service.UserService;
import org.springframework.stereotype.Service;

/**
 * 用户 Service 实现 — 用户管理的业务逻辑。
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Override
    public User getByUsername(String username) {
        return lambdaQuery()
                .eq(User::getUsername, username)
                // MyBatis-Plus 全局配置 logic-delete-field + logic-not-delete-value
                // 会自动追加 WHERE deleted = 0，此处无需重复过滤
                .one();
    }
}
