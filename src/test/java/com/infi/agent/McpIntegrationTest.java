package com.infi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.config.McpProperties;
import com.infi.agent.mcp.McpToolCatalog;
import com.infi.agent.mcp.server.McpStdioServer;
import com.infi.agent.multi.AgentRole;
import com.infi.agent.react.tool.ReactToolRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用本地真实 HTTP 协议替身验证握手、发现、调用、错误及白名单边界。 */
class McpIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> calls = new CopyOnWriteArrayList<>();
    private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private McpToolCatalog catalog;
    private boolean remoteError;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (catalog != null) {
            catalog.close();
        }
        server.stop(0);
    }

    @Test
    void discoversOnlyAllowedToolsAndRoutesOriginalNameAndArguments() {
        catalog = new McpToolCatalog(properties());
        ReactToolRegistry registry = new ReactToolRegistry(List.of(), mapper, catalog);
        assertThat(registry.names()).containsExactly("mcp_demo_echo");
        assertThat(registry.specifications().get(0).parameters().required()).containsExactly("text");
        ReactToolRegistry.ToolObservation observation = registry.execute(request("mcp_demo_echo", "{\"text\":\"你好\"}"));
        assertThat(observation.success()).isTrue();
        assertThat(observation.result()).contains("你好");
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).path("params").path("name").asText()).isEqualTo("echo");
        assertThat(calls.get(0).path("params").path("arguments").path("text").asText()).isEqualTo("你好");
        assertThat(authorizationHeaders).isNotEmpty().allMatch("Bearer test-token"::equals);
        assertThat(registry.execute(request("mcp_demo_delete", "{}")).success()).isFalse();
        assertThat(registry.execute(request("mcp_demo_echo", "[]")).success()).isFalse();
        assertThat(calls).hasSize(1);
    }

    @Test
    void remoteToolErrorBecomesFailedObservation() {
        remoteError = true;
        catalog = new McpToolCatalog(properties());
        ReactToolRegistry registry = new ReactToolRegistry(List.of(), mapper, catalog);
        assertThat(registry.execute(request("mcp_demo_echo", "{\"text\":\"test\"}")).success()).isFalse();
    }

    @Test
    void stdioTransportStartsServerAndCallsTool() {
        McpProperties properties = properties();
        properties.setTimeout(Duration.ofSeconds(5));
        McpProperties.Server connection = properties.getServers().get(0);
        connection.setTransport(McpProperties.Transport.STDIO);
        connection.setCommand(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), McpStdioServerStub.class.getName()));
        catalog = new McpToolCatalog(properties);
        ReactToolRegistry registry = new ReactToolRegistry(List.of(), mapper, catalog);
        ReactToolRegistry.ToolObservation result = registry.execute(request("mcp_demo_echo", "{\"text\":\"stdio\"}"));
        assertThat(result.success()).isTrue();
        assertThat(result.result()).contains("stdio");
    }

    @Test
    void productionStdioServerIsDiscoverableAndReturnsRealCalculation() {
        McpProperties properties = properties();
        properties.setTimeout(Duration.ofSeconds(5));
        McpProperties.Server connection = properties.getServers().get(0);
        connection.setTransport(McpProperties.Transport.STDIO);
        connection.setAllowedTools(List.of("calculator", "current_time"));
        connection.setCommand(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), McpStdioServer.class.getName()));
        catalog = new McpToolCatalog(properties);
        ReactToolRegistry registry = new ReactToolRegistry(List.of(), mapper, catalog);
        assertThat(registry.names()).containsExactly("mcp_demo_calculator", "mcp_demo_current_time");
        ReactToolRegistry.ToolObservation result = registry.execute(request("mcp_demo_calculator",
                "{\"left\":0.1,\"right\":0.2,\"operator\":\"ADD\"}"));
        assertThat(result.success()).isTrue();
        assertThat(result.result()).isEqualTo("0.3");
        assertThat(registry.execute(request("mcp_demo_calculator",
                "{\"left\":1,\"right\":0,\"operator\":\"DIVIDE\"}")).success()).isFalse();
    }

    @Test
    void disabledConfigurationDoesNotContactServer() {
        McpProperties properties = properties();
        properties.setEnabled(false);
        catalog = new McpToolCatalog(properties);
        assertThat(catalog.tools()).isEmpty();
        assertThat(authorizationHeaders).isEmpty();
    }

    @Test
    void invalidConfigurationIsRejectedBeforeConnecting() {
        McpProperties properties = properties();
        properties.getServers().get(0).setAllowedTools(List.of());
        assertThatThrownBy(() -> new McpToolCatalog(properties)).isInstanceOf(IllegalArgumentException.class);
        assertThat(authorizationHeaders).isEmpty();
        properties.getServers().get(0).setAllowedTools(List.of("echo"));
        properties.setServers(List.of(properties.getServers().get(0), properties.getServers().get(0)));
        assertThatThrownBy(() -> new McpToolCatalog(properties)).isInstanceOf(IllegalArgumentException.class);
        assertThat(authorizationHeaders).isEmpty();
    }

    @Test
    void missingAuthorizedToolFailsStartup() {
        McpProperties properties = properties();
        properties.getServers().get(0).setAllowedTools(List.of("missing"));
        assertThatThrownBy(() -> new McpToolCatalog(properties)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("test-token");
    }

    @Test
    void mcpRoleCannotUseBuiltinToolsAndOtherRolesCannotUseMcpTools() {
        assertThat(AgentRole.MCP.allowsTool("mcp_demo_echo")).isTrue();
        assertThat(AgentRole.MCP.allowsTool("file_delete")).isFalse();
        for (AgentRole role : AgentRole.values()) {
            if (role != AgentRole.MCP) {
                assertThat(role.allowsTool("mcp_demo_echo")).isFalse();
            }
        }
    }

    private McpProperties properties() {
        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        properties.setTimeout(Duration.ofSeconds(2));
        McpProperties.Server connection = new McpProperties.Server();
        connection.setName("demo");
        connection.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        connection.setHeaders(Map.of("Authorization", "Bearer test-token"));
        connection.setAllowedTools(List.of("echo"));
        properties.setServers(List.of(connection));
        return properties;
    }

    private ToolExecutionRequest request(String name, String arguments) {
        return ToolExecutionRequest.builder().id("call-1").name(name).arguments(arguments).build();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            if (!request.has("id")) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            Object result;
            switch (request.path("method").asText()) {
                case "initialize" -> result = Map.of("protocolVersion", "2025-11-25",
                        "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "test", "version", "1.0"));
                case "tools/list" -> result = Map.of("tools", List.of(
                        Map.of("name", "echo", "description", "Echo text", "inputSchema",
                                Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")),
                                        "required", List.of("text"))),
                        Map.of("name", "delete", "description", "Not authorized", "inputSchema",
                                Map.of("type", "object", "properties", Map.of()))));
                case "tools/call" -> {
                    calls.add(request);
                    result = Map.of("isError", remoteError, "content", List.of(Map.of("type", "text", "text",
                            request.path("params").path("arguments").path("text").asText())));
                }
                default -> {
                    respond(exchange, Map.of("jsonrpc", "2.0", "id", request.get("id"),
                            "error", Map.of("code", -32601, "message", "Method not found")));
                    return;
                }
            }
            respond(exchange, Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
        }
    }

    private void respond(HttpExchange exchange, Object response) throws IOException {
        byte[] bytes = mapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
