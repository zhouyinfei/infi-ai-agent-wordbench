package com.infi.agent.config;

import com.infi.agent.mcp.McpToolCatalog;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册 MCP 配置及由 Spring 负责回收的客户端目录。 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpConfiguration {
    /**
     * 创建 MCP 工具目录，容器销毁时关闭远程连接与子进程。
     *
     * @param properties 服务端连接配置
     * @return 不可由前端修改的工具目录
     */
    @Bean(destroyMethod = "close")
    public McpToolCatalog mcpToolCatalog(McpProperties properties) {
        return new McpToolCatalog(properties);
    }
}
