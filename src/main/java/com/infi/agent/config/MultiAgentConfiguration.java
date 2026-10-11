package com.infi.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册协作预算配置，沿用现有模型和工具基础设施。 */
@Configuration
@EnableConfigurationProperties(MultiAgentProperties.class)
public class MultiAgentConfiguration {
}
