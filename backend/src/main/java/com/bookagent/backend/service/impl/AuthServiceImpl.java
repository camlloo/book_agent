package com.bookagent.backend.service.impl;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bookagent.backend.common.exception.BusinessException;
import com.bookagent.backend.mapper.UserMapper;
import com.bookagent.backend.model.dto.LoginDTO;
import com.bookagent.backend.model.entity.User;
import com.bookagent.backend.model.vo.UserVO;
import com.bookagent.backend.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public SaTokenInfo login(LoginDTO dto) {
        if(dto.getPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw new BusinessException(400,"密码编码后不能超过72字节");
        //1.安用户名查库
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername())
        );
        //2.查无此人 或者密码错误提示同一句话
        if(user == null||!passwordEncoder.matches(dto.getPassword(),user.getPassword())){
            throw new BusinessException(401,"用户名或者密码错误");
        }
        //3.账号被禁用
        if(user.getStatus()==0){
            throw new BusinessException(403,"账号存在异常");
        }
        //4.登录：绑定的是user表的真实id
        StpUtil.login(user.getId());
        return StpUtil.getTokenInfo();
    }

    @Override
    public UserVO me() {
        long userId = StpUtil.getLoginIdAsLong();   // 从 token 反查登录时绑定的 ID
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(401, "登录状态异常，请重新登录");
        }
        return  BeanUtil.copyProperties(user, UserVO.class);
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }
}
