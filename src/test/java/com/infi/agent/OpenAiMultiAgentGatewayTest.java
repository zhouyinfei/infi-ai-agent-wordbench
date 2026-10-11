package com.infi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.config.AiModelProperties;
import com.infi.agent.config.MultiAgentProperties;
import com.infi.agent.dto.MultiAgentRunDTO;
import com.infi.agent.react.model.OpenAiReactModelGateway;
import com.infi.agent.react.tool.CalculatorTool;
import com.infi.agent.react.tool.ReactToolRegistry;
import com.infi.agent.service.impl.MultiAgentServiceImpl;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 实际 LangChain4j 客户端与本地 HTTP 模型替身验证计划及工具协议。 */
class OpenAiMultiAgentGatewayTest {
    @Test
    void nativeAdapterSendsPlanToolsObservationAndSummaryInOrder() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            int index = calls.getAndIncrement();
            Map<String, Object> message = switch (index) {
                case 0 -> Map.of("role", "assistant", "content", "{\"tasks\":[{\"agent\":\"CALCULATOR\",\"goal\":\"计算1加2\"}]}");
                case 1 -> Map.of("role", "assistant", "tool_calls", List.of(
                        Map.of("id", "calc", "type", "function", "function", Map.of("name", "calculator",
                                "arguments", "{\"left\":1,\"operator\":\"ADD\",\"right\":2}"))));
                case 2 -> Map.of("role", "assistant", "content", "结果为3");
                default -> Map.of("role", "assistant", "content",
                        "{\"approved\":true,\"answer\":\"结果为3\",\"reason\":\"计算证据一致\"}");
            };
            byte[] response = mapper.writeValueAsBytes(Map.of("id", "local-plan", "object", "chat.completion",
                    "created", 1, "model", "local-test",
                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15), "choices",
                    List.of(Map.of("index", 0, "message", message,
                            "finish_reason", index == 1 ? "tool_calls" : "stop"))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AiModelProperties model = new AiModelProperties();
            model.setEnabled(true);
            model.setApiKey("local-test-only");
            model.setModelName("local-test");
            model.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            model.setRequestTimeout(Duration.ofSeconds(2));
            var service = new MultiAgentServiceImpl(new OpenAiReactModelGateway(model),
                    new ReactToolRegistry(List.of(new CalculatorTool()), mapper), new MultiAgentProperties(), mapper, model);
            var result = service.execute(7L, new MultiAgentRunDTO("计算1加2"));
            assertThat(result.status()).isEqualTo("SUCCEEDED");
            assertThat(result.modelCalls()).isEqualTo(4);
            assertThat(result.tokenUsage().inputTokens()).isEqualTo(40L);
            assertThat(result.tokenUsage().outputTokens()).isEqualTo(20L);
            assertThat(result.tokenUsage().totalTokens()).isEqualTo(60L);
            assertThat(result.tokenUsage().complete()).isTrue();
            assertThat(result.toolCalls()).isEqualTo(1);
            assertThat(result.answer()).contains("3");
            assertThat(requests).hasSize(4);
            assertThat(requests.get(0).path("tools").isMissingNode()).isTrue();
            assertThat(requests.get(1).path("tools").get(0).path("function").path("name").asText())
                    .isEqualTo("calculator");
            assertThat(requests.get(2).path("messages")).anySatisfy(message -> {
                assertThat(message.path("role").asText()).isEqualTo("tool");
                assertThat(message.path("tool_call_id").asText()).isEqualTo("calc");
                assertThat(message.path("content").asText()).isEqualTo("3");
            });
            assertThat(requests.get(3).path("tools").isMissingNode()).isTrue();
            assertThat(requests.get(3).path("messages").toString()).contains("结果为3");
        } finally {
            server.stop(0);
        }
    }
}

