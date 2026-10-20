package com.infi.agent.react.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.multi.AgentRole;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

/** 使用本地搜索服务替身验证协议、凭证隔离、边界与注册行为。 */
class WebSearchToolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private URI endpoint;
    private final AtomicReference<String> request = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private int status = 200;
    private String contentType = "application/json";
    private boolean delayResponse;
    private String response = "{\"results\":[{\"title\":\"官方资料\",\"url\":\"https://example.com/docs\",\"content\":\"摘要\"}]}";

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayResponse) {
                try { Thread.sleep(11_000); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
            exchange.getResponseHeaders().set("Content-Type", contentType);
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search");
    }

    @AfterEach void stop() { server.stop(0); }

    private WebSearchTool tool() { return new WebSearchTool(mapper, "test-secret", 2, endpoint); }

    @Test void searchesWithServerCredentialAndReturnsSources() throws Exception {
        var registry = new ReactToolRegistry(List.of(tool()), mapper);
        var observation = registry.execute(dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .name("web_search").arguments("{\"query\":\" Java 官方资料 \"}").build());
        assertThat(observation.success()).isTrue();
        assertThat(observation.result()).contains("不可信", "https://example.com/docs", "摘要").doesNotContain("test-secret");
        assertThat(method.get()).isEqualTo("POST");
        assertThat(authorization.get()).isEqualTo("Bearer test-secret");
        var body = mapper.readTree(request.get());
        assertThat(body.path("query").asText()).isEqualTo("Java 官方资料");
        assertThat(body.path("max_results").asInt()).isEqualTo(2);
        assertThat(body.path("include_raw_content").asBoolean()).isFalse();
        assertThat(tool().specification().parameters().required()).containsExactly("query");
        assertThat(AgentRole.NETWORK.allowsTool("web_search")).isTrue();
        assertThat(AgentRole.FILE.allowsTool("web_search")).isFalse();
    }

    @Test void limitsResultsAndSummariesAndIgnoresInvalidLinks() throws Exception {
        response = mapper.writeValueAsString(Map.of("results", List.of(
                Map.of("url", "javascript:alert(1)"),
                Map.of("url", "https://example.com/1", "content", "x".repeat(3000)),
                Map.of("url", "https://example.com/2"), Map.of("url", "https://example.com/3"))));
        String output = tool().search("test");
        var results = mapper.readTree(output.substring(output.indexOf('\n') + 1)).path("results");
        assertThat(results.size()).isEqualTo(2);
        assertThat(results.get(0).path("content").asText()).hasSize(2001);
        assertThat(output).doesNotContain("javascript:", "https://example.com/3");
    }

    @Test void reportsEmptyResultsAndRejectsMalformedOrOversizedResponses() {
        response = "{\"results\":[]}";
        assertThat(tool().search("test")).contains("未找到可用结果");
        response = "{}";
        assertThatThrownBy(() -> tool().search("test")).hasMessageContaining("results");
        response = "invalid test-secret";
        assertThatThrownBy(() -> tool().search("test")).hasMessageContaining("响应格式").hasMessageNotContaining("test-secret");
        response = "x".repeat(262_145);
        assertThatThrownBy(() -> tool().search("test")).hasMessageContaining("256 KiB");
    }

    @Test void reportsHttpFailuresWithoutLeakingResponseBodies() {
        response = "test-secret private detail";
        for (int code : List.of(401, 403, 429, 302, 500)) {
            status = code;
            assertThatThrownBy(() -> tool().search("test")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("test-secret").hasMessageNotContaining("private detail");
        }
    }

    @Test void rejectsNonJsonResponses() {
        contentType = "text/html";
        assertThatThrownBy(() -> tool().search("test")).hasMessageContaining("未返回 JSON");
    }

    @Test void timesOutWithoutExposingCredentials() {
        delayResponse = true;
        assertThatThrownBy(() -> tool().search("test")).hasMessageContaining("超时").hasMessageNotContaining("test-secret");
    }

    @Test void rejectsInvalidArgumentsBeforeRequesting() throws Exception {
        assertThatThrownBy(() -> tool().search(" ")).hasMessageContaining("关键词");
        assertThatThrownBy(() -> tool().search("x".repeat(501))).hasMessageContaining("关键词");
        assertThatThrownBy(() -> tool().execute(mapper.readTree("{\"query\":\"test\",\"url\":\"https://evil.com\"}")))
                .hasMessageContaining("未知参数");
        assertThatThrownBy(() -> tool().execute(mapper.readTree("{\"query\":1}"))).hasMessageContaining("字符串");
        assertThat(request.get()).isNull();
    }

    @Test void registersOnlyWhenEnabledAndRequiresValidConfiguration() {
        var context = new ApplicationContextRunner().withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(WebSearchTool.class);
        context.run(result -> assertThat(result).doesNotHaveBean(WebSearchTool.class));
        context.withPropertyValues("app.tools.search.enabled=true", "app.tools.search.api-key=test-secret")
                .run(result -> assertThat(result).hasSingleBean(WebSearchTool.class));
        context.withPropertyValues("app.tools.search.enabled=true")
                .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("app.tools.search.enabled=true", "app.tools.search.api-key=test-secret",
                "app.tools.search.max-results=11").run(result -> assertThat(result).hasFailed());
    }
}
