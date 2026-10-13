package com.infi.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.multi.AgentRegistry;
import com.infi.agent.multi.DefaultAgentExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册协作预算和独立Agent实例，沿用现有模型及工具基础设施。 */
@Configuration
@EnableConfigurationProperties(MultiAgentProperties.class)
public class MultiAgentConfiguration {
    /**
     * 创建固定团队注册表；可替换为按专业实现注册的团队。
     *
     * @param executor 无状态的通用执行器
     * @param objectMapper JSON解析器
     * @return 包含全部独立角色实例的团队
     */
    @Bean
    public AgentRegistry agentRegistry(DefaultAgentExecutor executor, ObjectMapper objectMapper) {
        return new AgentRegistry(executor, objectMapper);
    }
}
