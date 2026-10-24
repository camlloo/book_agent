package com.bookagent.backend.common.exception;


import cn.dev33.satoken.exception.NotLoginException;
import com.bookagent.backend.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler({cn.dev33.satoken.exception.NotRoleException.class,cn.dev33.satoken.exception.NotPermissionException.class})
    public Result<Void> forbidden(Exception e){return Result.error(403,"无权限");}

    @ExceptionHandler({org.springframework.web.multipart.MaxUploadSizeExceededException.class})
    public Result<Void> oversized(Exception e, jakarta.servlet.http.HttpServletRequest request){
        return Result.error(400, request.getRequestURI().startsWith("/api/books/")
            ? "Book 文件不能超过 20MB" : "图片大小不能超过 5MB");
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.multipart.support.MissingServletRequestPartException.class,
        org.springframework.validation.BindException.class})
    public Result<Void> malformed(Exception e){return Result.error(400,"请求参数不合法");}

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public Result<Void> duplicate(Exception e){return Result.error(400,"名称或关联已存在");}

    /** 业务异常：预期内的错误，warn 一行即可，不打堆栈（堆栈是给"意外"用的） */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /** @Valid 校验失败：取第一条字段错误提示返回 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleInvalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getDefaultMessage())
                .orElse("参数不合法");
        log.warn("参数校验失败: {}", msg);
        return Result.error(400, msg);
    }

    /** Sa-Token 拦截器抛的未登录异常（注意包名是 cn.dev33.satoken.exception） */
    @ExceptionHandler(NotLoginException.class)
    public Result<Void> handleNotLogin(NotLoginException e) {
        log.warn("未登录访问: {}", e.getMessage());
        return Result.error(401, "未登录或登录已过期");
    }

    /** 请求路径不存在：返回 404，避免掉进 500 兜底造成语义错误 */
    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNotFound(NoHandlerFoundException e) {
        log.warn("接口不存在: {}", e.getRequestURL());
        return Result.error(404, "接口不存在");
    }

    /** 静态资源不存在（如浏览器自动请求的 favicon.ico）：debug 一行即可，不打堆栈、不刷 ERROR */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResource(NoResourceFoundException e) {
        log.debug("静态资源不存在: {}", e.getResourcePath());
        return Result.error(404, "资源不存在");
    }

    /** 兜底：预期外的异常。日志必须带完整堆栈，但响应体绝不能暴露内部信息 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e) {
        log.error("系统未预期异常", e);
        return Result.error("系统繁忙，请稍后重试");
    }
}
