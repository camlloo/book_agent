package com.bookagent.backend.common.constant;

public final class ApiConstants {

    private ApiConstants() {}

    /** 管理端业务接口统一前缀（需登录区） */
    public static final String API = "/api";

    /** 博客公开接口前缀（免登录区） */
    public static final String BLOG = "/api/blog";

    /** 内部接口前缀（供 Python AI 服务回调，独立于 /api） */
    public static final String INTERNAL = "/internal";
}