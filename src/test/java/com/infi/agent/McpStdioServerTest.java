package com.infi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.mcp.server.McpStdioServer;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 验证生产 stdio 服务器的生命周期、协议边界及真实工具结果。 */
class McpStdioServerTest {
    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
            "capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}
            """.replace("\n", "");
    private static final String INITIALIZED = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void handshakeListsSchemasAndExecutesRealTools() throws IOException {
        List<JsonNode> responses = run(INITIALIZE, INITIALIZED,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                call("sum", "calculator", "{\"left\":0.1,\"right\":0.2,\"operator\":\"ADD\"}"),
                call("time", "current_time", "{\"timezone\":\"UTC\"}"));
        assertThat(responses).hasSize(4);
        assertThat(responses.get(0).path("result").path("protocolVersion").asText()).isEqualTo("2025-11-25");
        assertThat(responses.get(1).path("result").path("tools")).hasSize(2);
        assertThat(responses.get(1).at("/result/tools/0/inputSchema/required")).hasSize(3);
        assertThat(responses.get(2).path("id").asText()).isEqualTo("sum");
        assertThat(responses.get(2).at("/result/content/0/text").asText()).isEqualTo("0.3");
        assertThat(responses.get(2).at("/result/isError").asBoolean()).isFalse();
        assertThat(responses.get(3).at("/result/content/0/text").asText()).contains("2026-10-19T00:00:00");
    }

    @Test
    void toolsRequireInitializedNotificationAndPingWorksBeforeHandshake() throws IOException {
        List<JsonNode> responses = run("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"ping\"}",
                INITIALIZE, call("early", "current_time", "{}"), INITIALIZED,
                call("ready", "current_time", "{}"));
        assertThat(responses.get(0).path("result").isObject()).isTrue();
        assertThat(responses.get(2).at("/error/code").asInt()).isEqualTo(-32002);
        assertThat(responses.get(3).at("/result/isError").asBoolean()).isFalse();
    }

    @Test
    void invalidJsonAndOversizedMessagesDoNotBreakFollowingRequests() throws IOException {
        List<JsonNode> responses = run("{broken", "x".repeat(400_001),
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}");
        assertThat(responses).hasSize(3);
        assertThat(responses.get(0).at("/error/code").asInt()).isEqualTo(-32700);
        assertThat(responses.get(1).at("/error/code").asInt()).isEqualTo(-32600);
        assertThat(responses.get(2).path("result").isObject()).isTrue();
    }

    @Test
    void toolErrorsAreDistinctFromProtocolErrors() throws IOException {
        List<JsonNode> responses = run(INITIALIZE, INITIALIZED,
                call("zero", "calculator", "{\"left\":1,\"right\":0,\"operator\":\"DIVIDE\"}"),
                call("unknown", "file_delete", "{}"),
                call("invalid", "calculator", "[]"),
                call("zone", "current_time", "{\"timezone\":\"invalid-zone\"}"));
        assertThat(responses.get(1).at("/result/isError").asBoolean()).isTrue();
        assertThat(responses.get(1).has("error")).isFalse();
        assertThat(responses.get(2).at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(responses.get(3).at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(responses.get(4).at("/result/isError").asBoolean()).isTrue();
    }

    @Test
    void ignoresNotificationsAndRejectsDiscoveryBatchesAndInvalidIds() throws IOException {
        List<JsonNode> responses = run("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/unknown\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":\"probe\",\"method\":\"server/discover\"}",
                "[]", "{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"ping\"}");
        assertThat(responses).hasSize(3);
        assertThat(responses.get(0).at("/error/code").asInt()).isEqualTo(-32601);
        assertThat(responses.get(1).at("/error/code").asInt()).isEqualTo(-32600);
        assertThat(responses.get(2).path("id").isNull()).isTrue();
    }

    @Test
    void rejectsInvalidInitializeDuplicateInitializeAndUnknownCursor() throws IOException {
        List<JsonNode> responses = run("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{}}",
                INITIALIZE, INITIALIZED, INITIALIZE,
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{\"cursor\":\"x\"}}");
        assertThat(responses.get(0).at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(responses.get(1).has("result")).isTrue();
        assertThat(responses.get(2).at("/error/code").asInt()).isEqualTo(-32600);
        assertThat(responses.get(3).at("/error/code").asInt()).isEqualTo(-32602);
    }

    private List<JsonNode> run(String... messages) throws IOException {
        Clock clock = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);
        StringWriter output = new StringWriter();
        new McpStdioServer(clock).run(new StringReader(String.join("\n", messages) + "\n"), output);
        List<JsonNode> responses = new ArrayList<>();
        for (String line : output.toString().lines().toList()) {
            responses.add(mapper.readTree(line));
        }
        return responses;
    }

    private String call(String id, String name, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"tools/call\",\"params\":{\"name\":\""
                + name + "\",\"arguments\":" + arguments + "}}";
    }
}
