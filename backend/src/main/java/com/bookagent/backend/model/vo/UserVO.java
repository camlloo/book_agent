package com.bookagent.backend.model.vo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

@Data
public class UserVO {
    /**
     *
     */
    private Long id;

    /**
     * 登录名
     */
    private String username;

    /**
     * 昵称（博客署名用）
     */
    private String nickname;
    /**
     * 角色：ADMIN / READER(预留)
     */
    private String role;
}
