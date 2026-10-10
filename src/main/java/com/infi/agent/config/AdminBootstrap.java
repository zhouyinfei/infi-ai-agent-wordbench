package com.infi.agent.config;

import com.infi.agent.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 只在空用户表且显式提供配置时初始化管理员。 */
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {
    private final UserService userService;
    @Value("${app.bootstrap.admin-username:}")
    private String username;
    @Value("${app.bootstrap.admin-password:}")
    private String password;

    @Override
    public void run(ApplicationArguments args) {
        if (!username.isBlank() && !password.isBlank()) {
            userService.bootstrapAdmin(username, password);
        }
    }
}
