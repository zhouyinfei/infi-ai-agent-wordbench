package com.infi.agent.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** ReAct 所需配置与可测试时钟。 */
@Configuration
@EnableConfigurationProperties({AiModelProperties.class, ReactProperties.class})
public class ReactConfiguration {
    @Bean
    public Clock reactClock() {
        return Clock.systemUTC();
    }
}
