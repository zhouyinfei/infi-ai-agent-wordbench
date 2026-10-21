package com.infi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.config.AiModelProperties;
import com.infi.agent.config.MultiAgentProperties;
import com.infi.agent.config.ReactProperties;
import com.infi.agent.dto.MultiAgentRunDTO;
import com.infi.agent.dto.PlanExecuteRunDTO;
import com.infi.agent.dto.ReactRunDTO;
import com.infi.agent.multi.AgentRegistry;
import com.infi.agent.multi.DefaultAgentExecutor;
import com.infi.agent.react.model.ReactModelGateway;
import com.infi.agent.react.tool.ReactTool;
import com.infi.agent.react.tool.ReactToolRegistry;
import com.infi.agent.service.impl.MultiAgentServiceImpl;
import com.infi.agent.service.impl.PlanExecuteServiceImpl;
import com.infi.agent.service.impl.ReactServiceImpl;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证内置联网工具的显式授权，禁止模型通过强制调用或角色分配绕过开关。 */
class NetworkSelectionTest {
    private final ObjectMapper json = new ObjectMapper();
    private final WebTool web = new WebTool();
    private final HttpTool http = new HttpTool();
    private ReactToolRegistry registry;
    private ReactModelGateway gateway;
    private ArrayDeque<AiMessage> responses;

    @BeforeEach
    void setUp() {
        registry = new ReactToolRegistry(List.of(web, http), json);
        gateway = mock(ReactModelGateway.class);
        when(gateway.isConfigured()).thenReturn(true);
        responses = new ArrayDeque<>();
        when(gateway.chat(any(), any())).thenAnswer(invocation -> ChatResponse.builder().aiMessage(responses.remove()).build());
    }

    @Test
    void disabledNetworkHidesAndBlocksBothToolsWithoutInvokingThem() {
        var scope = registry.newKnowledgeScope(false, false);
        assertThat(registry.specifications(scope)).isEmpty();
        for (String tool : List.of("web_search", "http_get")) {
            var observation = registry.execute(request(tool), scope);
            assertThat(observation.execution()).isEqualTo("NETWORK_BLOCKED");
        }
        assertThat(web.calls + http.calls).isZero();
    }

    @Test
    void enabledNetworkAllowsBothToolsIndependentlyOfKnowledgeSelection() {
        var scope = registry.newKnowledgeScope(false, true);
        assertThat(registry.specifications(scope)).hasSize(2);
        assertThat(registry.execute(request("web_search"), scope).success()).isTrue();
        assertThat(registry.execute(request("http_get"), scope).success()).isTrue();
        assertThat(web.calls + http.calls).isEqualTo(2);
    }

    @Test
    void reactRespectsBothOffAndOnSelections() {
        var service = new ReactServiceImpl(gateway, registry, new ReactProperties(), new AiModelProperties());
        for (boolean enabled : new boolean[]{false, true}) {
            responses.add(AiMessage.from(request("web_search")));
            responses.add(AiMessage.from("回答"));
            assertThat(service.execute(7L, new ReactRunDTO("问题", null, false, enabled)).status()).isEqualTo("SUCCEEDED");
            assertThat(web.calls).isEqualTo(enabled ? 1 : 0);
        }
    }

    @Test
    void planRespectsBothOffAndOnSelections() {
        var service = new PlanExecuteServiceImpl(gateway, registry, new ReactProperties(), json);
        for (boolean enabled : new boolean[]{false, true}) {
            responses.add(AiMessage.from("{\"steps\":[\"读取网页\"]}"));
            responses.add(AiMessage.from(request("http_get")));
            responses.add(AiMessage.from("回答"));
            responses.add(AiMessage.from("总结"));
            assertThat(service.execute(7L, new PlanExecuteRunDTO("问题", null, false, enabled)).status()).isEqualTo("SUCCEEDED");
            assertThat(http.calls).isEqualTo(enabled ? 1 : 0);
        }
    }

    @Test
    void multiAgentOnlyDelegatesNetworkRoleWhenEnabled() {
        var agents = new AgentRegistry(new DefaultAgentExecutor(gateway, registry, json), json);
        var service = new MultiAgentServiceImpl(gateway, registry, new MultiAgentProperties(), agents, new AiModelProperties());
        String assignment = "{\"tasks\":[{\"agent\":\"NETWORK\",\"goal\":\"查询网页\"}]}";
        responses.add(AiMessage.from(assignment));
        assertThat(service.execute(7L, new MultiAgentRunDTO("问题")).stopReason()).contains("未开启联网");
        assertThat(web.calls).isZero();
        responses.add(AiMessage.from(assignment));
        responses.add(AiMessage.from(request("web_search")));
        responses.add(AiMessage.from("网络资料"));
        responses.add(AiMessage.from("{\"approved\":true,\"answer\":\"回答\",\"reason\":\"证据通过\"}"));
        assertThat(service.execute(7L, new MultiAgentRunDTO("问题", null, false, true)).status()).isEqualTo("SUCCEEDED");
        assertThat(web.calls).isOne();
    }

    private static ToolExecutionRequest request(String name) {
        return ToolExecutionRequest.builder().id(name).name(name).arguments("{}").build();
    }

    static class WebTool implements ReactTool {
        int calls;
        @Tool(name = "web_search", value = "测试搜索")
        public String search() { calls++; return "网络资料"; }
        @Override public String execute(JsonNode arguments) { return search(); }
    }

    static class HttpTool implements ReactTool {
        int calls;
        @Tool(name = "http_get", value = "测试网页读取")
        public String get() { calls++; return "网页正文"; }
        @Override public String execute(JsonNode arguments) { return get(); }
    }
}
