package com.infi.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.infi.agent.react.tool.ReactTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;

/** 将 MCP 工具适配到统一工具执行入口，保留服务端原始工具名称。 */
public final class McpReactTool implements ReactTool {
    private final McpClient client;
    private final String remoteName;
    private final ToolSpecification specification;

    /**
     * 创建带命名空间的工具描述。
     *
     * @param serverName 服务端配置的唯一服务器名称
     * @param client 已初始化的 MCP 客户端
     * @param remoteSpecification 服务端发现的原始工具描述
     */
    public McpReactTool(String serverName, McpClient client, ToolSpecification remoteSpecification) {
        this.client = client;
        this.remoteName = remoteSpecification.name();
        this.specification = remoteSpecification.toBuilder()
                .name("mcp_" + serverName + "_" + remoteName).build();
    }

    /** @return 带服务器命名空间及原始参数结构的工具描述 */
    @Override
    public ToolSpecification specification() {
        return specification;
    }

    /**
     * 调用远程工具，远程错误及超时均转换为失败观察。
     *
     * @param arguments 统一入口已校验的 JSON 对象参数
     * @return 远程工具返回的文本结果
     */
    @Override
    public String execute(JsonNode arguments) {
        ToolExecutionResult result = client.executeTool(ToolExecutionRequest.builder()
                .name(remoteName).arguments(arguments.toString()).build());
        if (result == null || result.isError()) {
            throw new IllegalStateException("MCP 工具执行失败");
        }
        return result.resultText();
    }
}
