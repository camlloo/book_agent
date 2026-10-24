package com.bookagent.backend.common.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.bookagent.backend.common.constant.ApiConstants;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.bookagent.backend.common.exception.BusinessException;

/**
 * 登录拦截配置：默认拦截一切，放行白名单（全部使用对外完整路径）。
 */
@Configuration
@lombok.RequiredArgsConstructor
public class SaTokenConfig implements WebMvcConfigurer {
    private final KnowledgeGuard guard;
    @Value("${book-agent.internal-token:}") private String internalToken;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                String supplied=request.getHeader("X-Internal-Token");
                if(internalToken.isBlank() || supplied==null || !MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
                    throw new BusinessException(403,"内部接口鉴权失败");
                return true;
            }
        }).addPathPatterns("/internal/**");
        registry.addInterceptor(new SaInterceptor(handle -> {
            StpUtil.checkLogin();
            String path=cn.dev33.satoken.context.SaHolder.getRequest().getRequestPath();
            if(path.startsWith("/api/admin/")) StpUtil.checkRole("ADMIN");
        }))
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/internal/**",
                        ApiConstants.BLOG + "/**",         // /api/blog/** 博客公开区（免登录）
                        ApiConstants.API + "/auth/login",
                        ApiConstants.API + "/auth/register",  // 登录口本身
                        ApiConstants.API + "/hello",       // 连通性自测
                        "/error",                           // 错误转发必须放行，否则异常时二次拦截
                        "/doc.html",                        // knife4j 文档页与静态资源
                        "/webjars/**",
                        "/favicon.ico",
                        "/v3/api-docs/**",                  // 文档数据（免登录才能在 doc/swagger 里调试）
                        "/swagger-ui/**",
                        "/swagger-ui.html"
                );
        registry.addInterceptor(guard).addPathPatterns("/api/**").excludePathPatterns("/api/blog/**","/api/auth/login","/api/auth/register","/api/hello");
    }
}
