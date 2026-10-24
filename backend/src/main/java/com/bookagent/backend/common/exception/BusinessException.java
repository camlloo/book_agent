package com.bookagent.backend.common.exception;

import lombok.Getter;

/**
 * 业务异常：业务规则不满足时主动抛出（如"用户名或密码错误"、"文章不存在"），
 * 由 GlobalExceptionHandler 统一转成 Result 返回。
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务错误码，语义与 Result.code 对齐：401 未登录、403 无权限、400 参数问题、500 失败 */
    private final int code;

    /** 只带提示语，默认 500 —— 与 Result.error(message) 对应 */
    public BusinessException(String message) {
        this(500, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}