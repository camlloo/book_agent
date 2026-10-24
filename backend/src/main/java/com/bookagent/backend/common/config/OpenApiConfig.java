package com.bookagent.backend.common.config;

import com.bookagent.backend.common.constant.ApiConstants;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文档全局配置：标题/版本、Authorize 鉴权按钮、接口分组。
 * 注解（@Tag/@Operation）只管单个接口的说明，全局项都在这里。
 */
@Configuration
public class OpenApiConfig {

    /** 与 sa-token.token-name 对应；Authorize 里直接填 token 本身，不要加 Bearer 前缀 */
    private static final String SECURITY_SCHEME_NAME = "Authorization";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Book_Agent 接口文档")
                        .description("业务接口需登录（Authorize 中填 /api/auth/login 返回的 tokenValue）；博客公开接口免登录；内部接口供 AI 服务回调")
                        .version("v1"))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(SECURITY_SCHEME_NAME)));
    }

    @Bean
    public GroupedOpenApi blogGroup() {
        return GroupedOpenApi.builder()
                .group("01-博客公开接口")
                .pathsToMatch(ApiConstants.BLOG + "/**")
                .build();
    }

    @Bean
    public GroupedOpenApi businessGroup() {
        return GroupedOpenApi.builder()
                .group("02-业务接口（需登录）")
                .pathsToMatch(ApiConstants.API + "/**")
                .pathsToExclude(ApiConstants.BLOG + "/**")
                .build();
    }

    @Bean
    public GroupedOpenApi internalGroup() {
        return GroupedOpenApi.builder()
                .group("03-内部接口")
                .pathsToMatch(ApiConstants.INTERNAL + "/**")
                .build();
    }
}
