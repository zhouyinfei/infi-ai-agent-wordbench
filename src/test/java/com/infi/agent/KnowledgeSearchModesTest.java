package com.infi.agent;

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
import com.infi.agent.react.tool.KnowledgeSearchTool;
import com.infi.agent.react.tool.ReactToolRegistry;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.service.KnowledgeService;
import com.infi.agent.service.impl.MultiAgentServiceImpl;
import com.infi.agent.service.impl.PlanExecuteServiceImpl;
import com.infi.agent.service.impl.ReactServiceImpl;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 用可控模型验证三种编排的任务边界，以及跨步骤和角色共享检索缓存。 */
class KnowledgeSearchModesTest {
    private final ObjectMapper json = new ObjectMapper();
    private KnowledgeService knowledge;
    private ReactModelGateway gateway;
    private ReactToolRegistry tools;
    private ArrayDeque<AiMessage> responses;
    private ReactProperties properties;

    @BeforeEach
    void setUp() {
        knowledge = mock(KnowledgeService.class);
        when(knowledge.search(anyLong(), anyString(), eq(5))).thenReturn(List.of());
        gateway = mock(ReactModelGateway.class);
        when(gateway.isConfigured()).thenReturn(true);
        responses = new ArrayDeque<>();
        when(gateway.chat(any(), any())).thenAnswer(invocation -> ChatResponse.builder().aiMessage(responses.remove()).build());
        tools = new ReactToolRegistry(List.of(new KnowledgeSearchTool(knowledge)), json);
        properties = new ReactProperties();
        properties.setMaxIterations(12);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(7L, "alice", "USER"), null, List.of()));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reactReusesRepeatedCallsBeyondOldRepeatLimitAndClearsBetweenTasks() {
        var service = new ReactServiceImpl(gateway, tools, properties, new AiModelProperties());
        for (int index = 0; index < 4; index++) {
            responses.add(call("call" + index, "问题"));
        }
        responses.add(AiMessage.from("没有相关证据"));
        var result = service.execute(7L, new ReactRunDTO("问题", null, true));
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.steps().stream().filter(step -> "复用本次任务知识库检索结果".equals(step.summary()))).hasSize(3);
        verify(knowledge, times(1)).search(7L, "问题", 5);
        responses.add(call("new", "问题"));
        responses.add(AiMessage.from("没有相关证据"));
        assertThat(service.execute(7L, new ReactRunDTO("问题", null, true)).status()).isEqualTo("SUCCEEDED");
        verify(knowledge, times(2)).search(7L, "问题", 5);
    }

    @Test
    void reactCapsActualSearchesAndRecordsBlockedAttempt() {
        var service = new ReactServiceImpl(gateway, tools, properties, new AiModelProperties());
        for (int index = 0; index < 4; index++) {
            responses.add(call("call" + index, "问题" + index));
        }
        responses.add(AiMessage.from("已有资料不足以回答"));
        var result = service.execute(7L, new ReactRunDTO("问题", null, true));
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.steps()).anySatisfy(step -> assertThat(step.summary()).contains("未再次请求服务"));
        verify(knowledge, times(3)).search(eq(7L), anyString(), eq(5));
    }

    @Test
    void planStepsShareOneCache() {
        responses.add(AiMessage.from("{\"steps\":[\"检索资料\",\"核对资料\"]}"));
        responses.add(call("one", "问题"));
        responses.add(AiMessage.from("缺少资料"));
        responses.add(call("two", "问题"));
        responses.add(AiMessage.from("仍然缺少资料"));
        responses.add(AiMessage.from("证据不足"));
        var service = new PlanExecuteServiceImpl(gateway, tools, properties, json);
        assertThat(service.execute(7L, new PlanExecuteRunDTO("问题", null, true)).status()).isEqualTo("SUCCEEDED");
        verify(knowledge, times(1)).search(7L, "问题", 5);
    }

    @Test
    void multipleKnowledgeAssignmentsShareOneCache() {
        responses.add(AiMessage.from("{\"tasks\":[{\"agent\":\"KNOWLEDGE\",\"goal\":\"查找资料\"},{\"agent\":\"KNOWLEDGE\",\"goal\":\"核对资料\"}]}"));
        responses.add(call("one", "问题"));
        responses.add(AiMessage.from("缺少资料"));
        responses.add(call("two", "问题"));
        responses.add(AiMessage.from("仍然缺少资料"));
        responses.add(AiMessage.from("{\"approved\":true,\"answer\":\"知识库暂无足够证据\",\"reason\":\"已如实说明\"}"));
        var agents = new AgentRegistry(new DefaultAgentExecutor(gateway, tools, json), json);
        var service = new MultiAgentServiceImpl(gateway, tools, new MultiAgentProperties(), agents, new AiModelProperties());
        assertThat(service.execute(7L, new MultiAgentRunDTO("问题", null, true)).status()).isEqualTo("SUCCEEDED");
        verify(knowledge, times(1)).search(7L, "问题", 5);
    }

    private static AiMessage call(String id, String query) {
        return AiMessage.from(ToolExecutionRequest.builder().id(id).name("knowledge_search")
                .arguments("{\"query\":\"" + query + "\"}").build());
    }

    @Test
    void reactDoesNotRetrieveWithoutExplicitSelectionEvenIfModelRequestsIt() {
        responses.add(call("forced", "问题"));
        responses.add(AiMessage.from("请先勾选知识库"));
        var service = new ReactServiceImpl(gateway, tools, properties, new AiModelProperties());
        assertThat(service.execute(7L, new ReactRunDTO("检索知识库")).status()).isEqualTo("SUCCEEDED");
        verifyNoInteractions(knowledge);
    }

    @Test
    void planCannotBypassDeselectedKnowledge() {
        responses.add(AiMessage.from("{\"steps\":[\"检索资料\"]}"));
        responses.add(call("forced", "问题"));
        responses.add(AiMessage.from("知识库未启用"));
        responses.add(AiMessage.from("请先勾选知识库"));
        var service = new PlanExecuteServiceImpl(gateway, tools, properties, json);
        assertThat(service.execute(7L, new PlanExecuteRunDTO("检索知识库")).status()).isEqualTo("SUCCEEDED");
        verifyNoInteractions(knowledge);
    }

    @Test
    void coordinatorCannotDelegateKnowledgeRoleWithoutUserSelection() {
        responses.add(AiMessage.from("{\"tasks\":[{\"agent\":\"KNOWLEDGE\",\"goal\":\"强制检索\"}]}"));
        var agents = new AgentRegistry(new DefaultAgentExecutor(gateway, tools, json), json);
        var service = new MultiAgentServiceImpl(gateway, tools, new MultiAgentProperties(), agents, new AiModelProperties());
        var result = service.execute(7L, new MultiAgentRunDTO("检索知识库"));
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.stopReason()).contains("未勾选知识库");
        verifyNoInteractions(knowledge);
    }
}
