package com.bookagent.backend.service;

import cn.dev33.satoken.stp.SaTokenInfo;
import com.bookagent.backend.model.dto.LoginDTO;
import com.bookagent.backend.model.vo.UserVO;
import org.springframework.stereotype.Service;


public interface AuthService {
    /** 登录：成功返回 token 信息 */
    SaTokenInfo login(LoginDTO dto);

    /** 当前登录用户信息 */
    UserVO me();

    /** 退出登录 */
    void logout();
}
