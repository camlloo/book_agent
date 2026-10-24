package com.bookagent.backend.controller;

import com.bookagent.backend.common.constant.ApiConstants;
import com.bookagent.backend.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
@Tag(name = "测试接口")
@RestController
@RequestMapping(ApiConstants.API)
public class TestController {
    @Operation(summary = "连通性自测")
    @GetMapping("/hello")
    public Result<String> hello(){
        return Result.ok("hello");
    }
}
