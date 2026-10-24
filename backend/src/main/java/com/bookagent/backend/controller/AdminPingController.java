package com.bookagent.backend.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.bookagent.backend.common.constant.ApiConstants;
import com.bookagent.backend.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiConstants.API + "/admin")
@SaCheckRole("ADMIN")
public class AdminPingController {
    @GetMapping("/ping")
    public Result<String> ping(){
        return Result.ok("pong");
    }
}
