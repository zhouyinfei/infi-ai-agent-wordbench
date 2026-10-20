package com.infi.agent.mcp;

import com.infi.agent.config.McpProperties;
import com.infi.agent.react.tool.ReactTool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 管理 MCP 连接生命周期；启动时发现明确授权的工具，失败时回收已创建连接。 */
public final class McpToolCatalog implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpToolCatalog.class);
    private static final Pattern SERVER_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9-]{1,20}");
    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_-]+");
    private static final int MAX_TOOL_NAME_LENGTH = 64;
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);
    private final List<McpClient> clients = new ArrayList<>();
    private final List<ReactTool> tools = new ArrayList<>();

    /**
     * 校验全部配置后建立连接，未启用时不执行任何网络请求或进程启动。
     *
     * @param properties 可信的服务端 MCP 配置
     */
    public McpToolCatalog(McpProperties properties) {
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            return;
        }
        validate(properties);
        try {
            for (McpProperties.Server server : properties.getServers()) {
                register(server, properties);
            }
        } catch (RuntimeException exception) {
            close();
            LOGGER.error("MCP 初始化失败，异常类型：{}", exception.getClass().getSimpleName());
            // 原始异常可能含请求头、URL 或命令参数，不能传播到启动日志。
            throw new IllegalStateException("MCP 初始化失败，请检查服务器连接及工具白名单");
        }
    }

    /** @return 启动时发现且通过白名单筛选的不可修改工具列表 */
    public List<ReactTool> tools() {
        return List.copyOf(tools);
    }

    /** 关闭全部客户端；单个连接关闭失败不影响其他连接回收。 */
    @Override
    public void close() {
        for (McpClient client : clients) {
            closeConnection(client);
        }
        clients.clear();
    }

    /** 创建连接并验证每个配置授权的工具确实存在。 */
    private void register(McpProperties.Server server, McpProperties properties) {
        McpTransport transport = server.getTransport() == McpProperties.Transport.STDIO
                ? StdioMcpTransport.builder().command(List.copyOf(server.getCommand()))
                        .environment(server.getEnvironment()).logEvents(false).build()
                : StreamableHttpMcpTransport.builder().url(server.getUrl()).customHeaders(server.getHeaders())
                        .timeout(properties.getTimeout()).logRequests(false).logResponses(false).build();
        McpClient client;
        try {
            client = DefaultMcpClient.builder().key(server.getName()).transport(transport)
                    .initializationTimeout(properties.getTimeout()).toolExecutionTimeout(properties.getTimeout())
                    .autoHealthCheck(false).build();
        } catch (RuntimeException exception) {
            closeConnection(transport);
            throw exception;
        }
        clients.add(client);
        Set<String> missingTools = new HashSet<>(server.getAllowedTools());
        for (ToolSpecification specification : client.listTools()) {
            if (server.getAllowedTools().contains(specification.name())) {
                tools.add(new McpReactTool(server.getName(), client, specification));
                missingTools.remove(specification.name());
            }
        }
        if (!missingTools.isEmpty()) {
            throw new IllegalStateException("MCP 服务器缺少已授权工具");
        }
    }

    /** 在产生外部副作用前拒绝不完整或不合法的配置。 */
    private static void validate(McpProperties properties) {
        if (properties.getTimeout() == null || properties.getTimeout().compareTo(Duration.ofMillis(1)) < 0
                || properties.getTimeout().compareTo(MAX_TIMEOUT) > 0 || properties.getServers() == null) {
            throw new IllegalArgumentException("MCP 超时须为 1 毫秒至 300 秒且服务器列表不能为空");
        }
        Set<String> names = new HashSet<>();
        for (McpProperties.Server server : properties.getServers()) {
            if (server == null || server.getName() == null
                    || !SERVER_NAME_PATTERN.matcher(server.getName()).matches() || !names.add(server.getName())) {
                throw new IllegalArgumentException("MCP 服务器名称须唯一且由 1 至 20 位字母、数字或短横线组成");
            }
            if (server.getTransport() == null || server.getAllowedTools() == null
                    || server.getAllowedTools().isEmpty()) {
                throw new IllegalArgumentException("MCP 须配置传输方式及非空工具白名单");
            }
            for (String toolName : server.getAllowedTools()) {
                if (toolName == null || !TOOL_NAME_PATTERN.matcher(toolName).matches()
                        || ("mcp_" + server.getName() + "_" + toolName).length() > MAX_TOOL_NAME_LENGTH) {
                    throw new IllegalArgumentException("MCP 工具名称不符合模型工具命名约束");
                }
            }
            if (server.getTransport() == McpProperties.Transport.STDIO) {
                if (server.getCommand() == null || server.getCommand().isEmpty()
                        || server.getCommand().stream().anyMatch(value -> value == null || value.isBlank())
                        || server.getEnvironment() == null) {
                    throw new IllegalArgumentException("MCP stdio 须配置有效命令及环境变量");
                }
            } else {
                validateHttp(server);
            }
        }
    }

    /** HTTP 端点仅允许显式 HTTP(S) 地址，凭证通过请求头配置。 */
    private static void validateHttp(McpProperties.Server server) {
        if (server.getUrl() == null || server.getUrl().isBlank() || server.getHeaders() == null) {
            throw new IllegalArgumentException("MCP 须配置有效 HTTP(S) 端点及请求头");
        }
        try {
            URI uri = URI.create(server.getUrl());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("MCP HTTP 配置无效");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("MCP 须配置有效 HTTP(S) 端点及请求头");
        }
    }

    /** 回收传输资源，日志不包含远程地址、凭证或进程命令。 */
    private static void closeConnection(AutoCloseable connection) {
        try {
            connection.close();
        } catch (IOException exception) {
            LOGGER.warn("MCP 连接关闭时发生 I/O 异常");
        } catch (Exception exception) {
            LOGGER.warn("MCP 连接关闭失败，异常类型：{}", exception.getClass().getSimpleName());
        }
    }
}
