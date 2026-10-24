package com.bookagent.backend.common.config;

import cn.dev33.satoken.stp.StpInterface;
import com.bookagent.backend.mapper.UserMapper;
import com.bookagent.backend.model.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 的"取数钩子"：@SaCheckRole("ADMIN") 执行时，框架回调本类拿当前用户的角色清单来比对。
 * 必须注册为 Bean（@Component），否则角色注解永远不通过。
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final UserMapper userMapper;

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        User user = userMapper.selectById(Long.parseLong(loginId.toString()));
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            return Collections.emptyList();
        }
        return List.of(user.getRole());
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return Collections.emptyList(); // 本项目权限粒度到角色为止，暂不使用 permission
    }
}
