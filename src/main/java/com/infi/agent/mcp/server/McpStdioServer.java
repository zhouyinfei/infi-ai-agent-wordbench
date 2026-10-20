package com.infi.agent.mcp.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infi.agent.react.tool.CalculatorTool;
import com.infi.agent.react.tool.CurrentTimeTool;
import com.infi.agent.react.tool.ReactToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * 独立的 stdio MCP 工具服务器，标准输出仅写入逐行 JSON-RPC 消息。
 *
 * @author Infi
 */
public final class McpStdioServer {
    private static final String PROTOCOL_VERSION = "2025-11-25";
    private static final String JSON_RPC_VERSION = "2.0";
    private static final int MAX_MESSAGE_LENGTH = 400_000;
    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int NOT_INITIALIZED = -32002;
    private final ObjectMapper objectMapper;
    private final ReactToolRegistry toolRegistry;
    private boolean initializationRequested;
    private boolean initialized;

    /**
     * 创建仅开放计算器和时间查询的服务器，不加载 Spring 容器。
     *
     * @param clock 时间工具使用的可测试时钟
     */
    public McpStdioServer(Clock clock) {
        objectMapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        toolRegistry = new ReactToolRegistry(List.of(new CalculatorTool(), new CurrentTimeTool(clock)), objectMapper);
    }

    /**
     * 启动 UTF-8 stdio 服务，输入关闭时结束进程。
     *
     * @param args 不接受启动参数
     * @throws IOException 标准输入输出失败
     */
    public static void main(String[] args) throws IOException {
        McpStdioServer server = new McpStdioServer(Clock.systemUTC());
        try (Reader input = new InputStreamReader(System.in, StandardCharsets.UTF_8);
                Writer output = new OutputStreamWriter(System.out, StandardCharsets.UTF_8)) {
            server.run(input, output);
        }
    }

    /**
     * 顺序处理单个客户端连接；超长消息会被完整丢弃以恢复下一行协议边界。
     *
     * @param input MCP 请求输入流，由调用方负责关闭
     * @param output MCP 响应输出流，由调用方负责关闭
     * @throws IOException 输入输出失败时终止连接
     */
    public void run(Reader input, Writer output) throws IOException {
        BufferedReader reader = new BufferedReader(input);
        BufferedWriter writer = new BufferedWriter(output);
        while (true) {
            StringBuilder message = new StringBuilder();
            boolean oversized = false;
            int character;
            while ((character = reader.read()) != -1 && character != '\n') {
                if (message.length() < MAX_MESSAGE_LENGTH) {
                    message.append((char) character);
                } else {
                    oversized = true;
                }
            }
            if (character == -1 && message.isEmpty() && !oversized) {
                return;
            }
            ObjectNode response = oversized ? error(null, INVALID_REQUEST, "消息长度超过限制")
                    : handle(message.toString());
            if (response != null) {
                writer.write(objectMapper.writeValueAsString(response));
                writer.write('\n');
                writer.flush();
            }
        }
    }

    /** 解析并校验请求信封，通知消息不产生响应。 */
    private ObjectNode handle(String message) {
        JsonNode request;
        try {
            request = objectMapper.readTree(message);
        } catch (JsonProcessingException exception) {
            return error(null, PARSE_ERROR, "JSON 解析失败");
        }
        if (request == null || !request.isObject()) {
            return error(null, INVALID_REQUEST, "请求须为 JSON-RPC 对象");
        }
        JsonNode id = request.get("id");
        if (id != null && !(id.isTextual() || id.isIntegralNumber())) {
            return error(null, INVALID_REQUEST, "请求 ID 须为字符串或整数");
        }
        if (!JSON_RPC_VERSION.equals(request.path("jsonrpc").asText()) || !request.path("method").isTextual()
                || (request.has("params") && !request.get("params").isObject())) {
            return id == null ? null : error(id, INVALID_REQUEST, "JSON-RPC 请求结构不合法");
        }
        String method = request.path("method").asText();
        if (id == null) {
            if ("notifications/initialized".equals(method) && initializationRequested) {
                initialized = true;
            }
            return null;
        }
        if ("ping".equals(method)) {
            return success(id, Map.of());
        }
        if ("initialize".equals(method)) {
            return initialize(id, request.path("params"));
        }
        // 对新版客户端的协议探测明确返回未知方法，以便其立即回退到 initialize。
        if (!List.of("tools/list", "tools/call").contains(method)) {
            return error(id, METHOD_NOT_FOUND, "不支持的方法");
        }
        if (!initialized) {
            return error(id, NOT_INITIALIZED, "请先完成 MCP 初始化");
        }
        return "tools/list".equals(method) ? listTools(id, request.path("params"))
                : callTool(id, request.path("params"));
    }

    /** 协商固定协议版本，等待客户端发送 initialized 通知。 */
    private ObjectNode initialize(JsonNode id, JsonNode params) {
        if (!params.path("protocolVersion").isTextual() || !params.path("capabilities").isObject()
                || !params.path("clientInfo").path("name").isTextual()
                || !params.path("clientInfo").path("version").isTextual()) {
            return error(id, INVALID_PARAMS, "initialize 缺少版本、能力或客户端信息");
        }
        if (initializationRequested) {
            return error(id, INVALID_REQUEST, "当前连接已经初始化");
        }
        initializationRequested = true;
        return success(id, Map.of("protocolVersion", PROTOCOL_VERSION, "capabilities",
                Map.of("tools", Map.of("listChanged", false)), "serverInfo",
                Map.of("name", "infi-stdio-tools", "version", "0.1.0")));
    }

    /** 返回静态工具目录，不生成分页游标。 */
    private ObjectNode listTools(JsonNode id, JsonNode params) {
        if (params.has("cursor")) {
            return error(id, INVALID_PARAMS, "工具目录不支持分页游标");
        }
        Map<String, Object> calculatorSchema = Map.of("type", "object", "properties", Map.of(
                "left", Map.of("type", "number", "description", "左操作数，绝对值不超过 1E20"),
                "right", Map.of("type", "number", "description", "右操作数，绝对值不超过 1E20"),
                "operator", Map.of("type", "string", "enum", List.of("ADD", "SUBTRACT", "MULTIPLY", "DIVIDE"))),
                "required", List.of("left", "operator", "right"), "additionalProperties", false);
        Map<String, Object> timeSchema = Map.of("type", "object", "properties", Map.of(
                "timezone", Map.of("type", "string", "description", "IANA 时区，默认 Asia/Shanghai")),
                "additionalProperties", false);
        return success(id, Map.of("tools", List.of(
                Map.of("name", "calculator", "description", "精确计算两个数的加减乘除", "inputSchema", calculatorSchema),
                Map.of("name", "current_time", "description", "查询指定时区当前时间", "inputSchema", timeSchema))));
    }

    /** 协议参数错误与工具业务错误分别按 JSON-RPC error 和 isError 表达。 */
    private ObjectNode callTool(JsonNode id, JsonNode params) {
        if (!params.path("name").isTextual()
                || (params.has("arguments") && !params.get("arguments").isObject())) {
            return error(id, INVALID_PARAMS, "工具名称须为字符串，arguments 须为对象");
        }
        String name = params.path("name").asText();
        if (!toolRegistry.names().contains(name)) {
            return error(id, INVALID_PARAMS, "工具不存在");
        }
        JsonNode arguments = params.has("arguments") ? params.get("arguments") : objectMapper.createObjectNode();
        ReactToolRegistry.ToolObservation result = toolRegistry.execute(ToolExecutionRequest.builder()
                .name(name).arguments(arguments.toString()).build());
        return success(id, Map.of("isError", !result.success(), "content",
                List.of(Map.of("type", "text", "text", result.result()))));
    }

    /** 创建保留原始请求 ID 类型的成功信封。 */
    private ObjectNode success(JsonNode id, Object result) {
        ObjectNode response = objectMapper.createObjectNode().put("jsonrpc", JSON_RPC_VERSION);
        response.set("id", id);
        response.set("result", objectMapper.valueToTree(result));
        return response;
    }

    /** 创建不暴露内部异常或原始请求内容的错误信封。 */
    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode().put("jsonrpc", JSON_RPC_VERSION);
        response.set("id", id == null ? objectMapper.nullNode() : id);
        response.set("error", objectMapper.valueToTree(Map.of("code", code, "message", message)));
        return response;
    }
}
