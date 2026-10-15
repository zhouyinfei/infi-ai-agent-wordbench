package com.infi.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 启用会话窗口配置；Redis连接沿用Spring Boot本地配置。 */
@Configuration
@EnableConfigurationProperties(ChatMemoryProperties.class)
public class ChatMemoryConfiguration {
}
