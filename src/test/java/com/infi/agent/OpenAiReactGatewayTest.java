package com.infi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.config.AiModelProperties;
import com.infi.agent.config.ReactProperties;
import com.infi.agent.dto.ReactRunDTO;
import com.infi.agent.react.model.OpenAiReactModelGateway;
import com.infi.agent.react.tool.CalculatorTool;
import com.infi.agent.react.tool.ReactToolRegistry;
import com.infi.agent.service.impl.ReactServiceImpl;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 通过本地 HTTP 模型替身验证真正的 LangChain4j 请求与工具消息协议。 */
class OpenAiReactGatewayTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ReactServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AiModelProperties model = new AiModelProperties();
        model.setEnabled(true);
        model.setApiKey("self-test-not-a-real-key");
        model.setModelName("self-test-model");
        model.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        model.setRequestTimeout(Duration.ofSeconds(2));
        service = new ReactServiceImpl(new OpenAiReactModelGateway(model),
                new ReactToolRegistry(List.of(new CalculatorTool()), objectMapper), new ReactProperties(), model);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void actualAdapterCompletesThreeRequestsAndPreservesToolIdsAndObservations() throws Exception {
        AtomicInteger index = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(objectMapper.readTree(exchange.getRequestBody()));
            int round = index.getAndIncrement();
            Map<String, Object> message;
            if (round < 2) {
                String arguments = round == 0 ? "{\"left\":128,\"operator\":\"ADD\",\"right\":256}"
                        : "{\"left\":384,\"operator\":\"MULTIPLY\",\"right\":3}";
                message = Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "call-" + round,
                        "type", "function", "function", Map.of("name", "calculator", "arguments", arguments))));
            } else {
                message = Map.of("role", "assistant", "content", "结果是1152。");
            }
            byte[] body = objectMapper.writeValueAsBytes(Map.of("id", "response-" + round,
                    "object", "chat.completion", "created", 1700000000, "model", "self-test-model",
                    "choices", List.of(Map.of("index", 0, "message", message,
                            "finish_reason", round < 2 ? "tool_calls" : "stop")),
                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 10, "total_tokens", 20)));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var result = service.execute(1L, new ReactRunDTO("先加再乘"));
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.answer()).contains("1152");
        assertThat(requests).hasSize(3);
        assertThat(requests.get(0).path("tools").get(0).path("function").path("name").asText())
                .isEqualTo("calculator");
        List<JsonNode> observations = new ArrayList<>();
        requests.get(2).path("messages").forEach(message -> {
            if ("tool".equals(message.path("role").asText())) {
                observations.add(message);
            }
        });
        assertThat(observations).hasSize(2);
        assertThat(observations.get(0).path("content").asText()).isEqualTo("384");
        assertThat(observations.get(0).path("tool_call_id").asText()).isEqualTo("call-0");
        assertThat(observations.get(1).path("content").asText()).isEqualTo("1152");
    }

    @Test
    void adapterFailureDoesNotLeakProviderBodyOrRetry() throws Exception {
        AtomicInteger count = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            count.incrementAndGet();
            byte[] body = "private-provider-detail".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var result = service.execute(1L, new ReactRunDTO("计算1加2"));
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.stopReason()).doesNotContain("private-provider-detail");
        assertThat(count).hasValue(1);
    }
}
