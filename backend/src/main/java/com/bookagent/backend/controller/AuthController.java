package com.bookagent.backend.controller;

import cn.dev33.satoken.stp.SaTokenInfo;
import com.bookagent.backend.common.result.Result;
import com.bookagent.backend.common.constant.ApiConstants;
import com.bookagent.backend.model.dto.LoginDTO;
import com.bookagent.backend.model.vo.UserVO;
import com.bookagent.backend.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(ApiConstants.API + "/auth")
@RequiredArgsConstructor
@Tag(name = "认证", description = "登录、登出、当前用户")
public class AuthController {
    private final AuthService authService;
    @PostMapping("/login")
    @Operation(summary = "登录")
    public Result<SaTokenInfo> login(@Valid @RequestBody LoginDTO dto){
        SaTokenInfo tokenInfo = authService.login(dto);
        return Result.ok(tokenInfo);
    }
    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.ok();
    }
    @Operation(summary = "当前登录用户")
    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(authService.me());
    }
}
