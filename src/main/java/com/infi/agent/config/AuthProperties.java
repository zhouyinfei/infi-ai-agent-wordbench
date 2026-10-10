package com.infi.agent.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 认证预算与有效期。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {
    private Duration tokenTtl = Duration.ofHours(8);
    private Duration loginWindow = Duration.ofMinutes(15);
    private int maxLoginAttempts = 10;
}
