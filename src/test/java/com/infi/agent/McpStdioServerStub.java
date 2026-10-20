package com.infi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 由 stdio 集成测试启动的最小 JSON-RPC 子进程，标准输出仅承载协议消息。 */
public final class McpStdioServerStub {
    private McpStdioServerStub() {
    }

    /**
     * 读取逐行 MCP 请求并返回握手、工具发现或调用结果。
     *
     * @param args 不使用启动参数
     * @throws IOException 标准输入输出失败
     */
    public static void main(String[] args) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode request = mapper.readTree(line);
                if (!request.has("id")) {
                    continue;
                }
                Object result;
                switch (request.path("method").asText()) {
                    case "initialize" -> result = Map.of("protocolVersion", "2025-11-25",
                            "capabilities", Map.of("tools", Map.of()),
                            "serverInfo", Map.of("name", "stdio-test", "version", "1.0"));
                    case "tools/list" -> result = Map.of("tools", List.of(Map.of("name", "echo",
                            "description", "Echo text", "inputSchema", Map.of("type", "object",
                                    "properties", Map.of("text", Map.of("type", "string"))))));
                    case "tools/call" -> result = Map.of("content", List.of(Map.of("type", "text", "text",
                            request.path("params").path("arguments").path("text").asText())));
                    default -> {
                        System.out.println(mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", request.get("id"),
                                "error", Map.of("code", -32601, "message", "Method not found"))));
                        System.out.flush();
                        continue;
                    }
                }
                System.out.println(mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", request.get("id"),
                        "result", result)));
                System.out.flush();
            }
        }
    }
}
