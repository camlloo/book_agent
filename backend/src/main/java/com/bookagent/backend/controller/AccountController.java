package com.bookagent.backend.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.bookagent.backend.common.exception.BusinessException;
import com.bookagent.backend.common.result.Result;
import com.bookagent.backend.service.TaxonomyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class AccountController {
    private final JdbcTemplate jdbc;private final BCryptPasswordEncoder encoder;private final TaxonomyService keys;
    public record Register(@NotBlank @Pattern(regexp="[A-Za-z0-9_]{3,32}") String username,@NotBlank @Size(min=8,max=64) String password,@Size(max=32) String nickname){}
    @PostMapping("/api/auth/register") @Transactional
    public Result<?> register(@Valid @RequestBody Register form){
        if(form.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw new BusinessException(400,"密码编码后不能超过72字节");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE username=?",Long.class,form.username())>0)throw new BusinessException(400,"用户名已被使用");
        long id=keys.insert("INSERT INTO `user`(username,password,nickname,role,status) VALUES(?,?,?,'USER',1)",form.username(),encoder.encode(form.password()),form.nickname()==null||form.nickname().isBlank()?form.username():form.nickname().trim());
        return Result.ok(java.util.Map.of("id",id,"username",form.username()));
    }
    public record Profile(@NotBlank @Size(max=32) String nickname){}
    @PutMapping("/api/auth/profile") public Result<?> profile(@Valid @RequestBody Profile form){jdbc.update("UPDATE `user` SET nickname=? WHERE id=?",form.nickname().trim(),StpUtil.getLoginIdAsLong());return Result.ok();}
}
