package com.bookagent.backend.common.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bookagent.backend.mapper.UserMapper;
import com.bookagent.backend.model.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 管理员种子数据：首次启动时若无 ADMIN 账号，按 yml 配置自动创建。
 * （BCrypt 散列无法手写，靠它实现"第一次启动即开即用"）
 */
@Slf4j
@Component
@org.springframework.core.annotation.Order(10)
@RequiredArgsConstructor
public class AdminInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${book-agent.admin.username}")
    private String adminUsername;

    @Value("${book-agent.admin.password}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        Long adminCount = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getRole, "ADMIN"));
        if (adminCount != null && adminCount > 0) {
            return; // 已有管理员，不重复创建
        }
        User admin = new User();
        admin.setUsername(adminUsername);
        admin.setPassword(passwordEncoder.encode(adminPassword));
        admin.setNickname("博主");
        admin.setRole("ADMIN");
        admin.setStatus(1);
        userMapper.insert(admin);
        log.info("已自动创建管理员账号: {}", adminUsername);
    }
}
