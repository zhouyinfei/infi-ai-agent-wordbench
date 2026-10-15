package com.infi.agent.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 会话记忆窗口与缓存有效期，完整轮次仍持久化到MySQL。 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.chat-memory")
public class ChatMemoryProperties {
    @Min(2)
    @Max(100)
    private int maxMessages = 20;
    @Min(4000)
    @Max(200000)
    private int maxCharacters = 32000;
    private Duration cacheTtl = Duration.ofHours(24);

    /** @return 窗口必须为偶数以保留完整轮次，缓存有效期必须为正 */
    @AssertTrue(message = "记忆窗口必须为偶数且缓存有效期为正")
    public boolean isWindowValid() {
        return maxMessages % 2 == 0 && cacheTtl != null && !cacheTtl.isNegative() && !cacheTtl.isZero();
    }
}

