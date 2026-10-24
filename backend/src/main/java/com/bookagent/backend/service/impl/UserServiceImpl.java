package com.bookagent.backend.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bookagent.backend.mapper.UserMapper;
import com.bookagent.backend.model.entity.User;

import com.bookagent.backend.service.UserService;
import org.springframework.stereotype.Service;

/**
* @author 12507
* @description 针对表【user(用户表)】的数据库操作Service实现
* @createDate 2026-09-29 22:03:47
*/
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
    implements UserService {

}




