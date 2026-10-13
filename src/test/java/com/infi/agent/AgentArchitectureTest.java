package com.infi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infi.agent.config.AiModelProperties;
import com.infi.agent.config.MultiAgentProperties;
import com.infi.agent.dto.MultiAgentRunDTO;
import com.infi.agent.multi.Agent;
import com.infi.agent.multi.AgentAssignment;
import com.infi.agent.multi.AgentEvidence;
import com.infi.agent.multi.AgentExecutionContext;
import com.infi.agent.multi.AgentRegistry;
import com.infi.agent.multi.AgentResult;
import com.infi.agent.multi.AgentReview;
import com.infi.agent.multi.AgentRole;
import com.infi.agent.multi.AgentTask;
import com.infi.agent.multi.DefaultAgentExecutor;
import com.infi.agent.react.model.ReactModelGateway;
import com.infi.agent.react.tool.CalculatorTool;
import com.infi.agent.react.tool.CurrentTimeTool;
import com.infi.agent.react.tool.ReactToolRegistry;
import com.infi.agent.service.impl.MultiAgentServiceImpl;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证Agent是可替换的独立执行主体，协调服务只通过任务交付协议进行委派。 */
class AgentArchitectureTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MultiAgentProperties properties = new MultiAgentProperties();
    private ReactModelGateway gateway;
    private ReactToolRegistry tools;
    private AgentRegistry agents;

    @BeforeEach
    void setUp() {
        gateway = mock(ReactModelGateway.class);
        when(gateway.isConfigured()).thenReturn(true);
        tools = new ReactToolRegistry(List.of(new CalculatorTool(), new CurrentTimeTool(Clock.systemUTC())), mapper);
        agents = new AgentRegistry(new DefaultAgentExecutor(gateway, tools, mapper), mapper);
    }

    @Test
    void fixedRolesHaveIndependentInstances() {
        assertThat(agents.agent(AgentRole.CALCULATOR)).isNotSameAs(agents.agent(AgentRole.TIME));
        assertThat(agents.agent(AgentRole.CALCULATOR)).isSameAs(agents.agent(AgentRole.CALCULATOR));
        assertThat(agents.agent(AgentRole.TIME).role()).isEqualTo(AgentRole.TIME);
    }

    @Test
    void workerCanExecuteDirectlyWithoutCoordinatorService() {
        AiMessage call = AiMessage.from(ToolExecutionRequest.builder().id("calc").name("calculator")
                .arguments("{\"left\":1,\"operator\":\"ADD\",\"right\":2}").build());
        when(gateway.chat(any(ChatRequest.class), any(Duration.class)))
                .thenReturn(response(call), response(AiMessage.from("3")));
        var context = new AgentExecutionContext(properties);
        var result = agents.agent(AgentRole.CALCULATOR)
                .execute(new AgentTask(1, "计算1加2", "计算1加2", List.of()), context);
        assertThat(result.successful()).isTrue();
        assertThat(result.agent()).isEqualTo(AgentRole.CALCULATOR);
        assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().get(0).result()).isEqualTo("3");
        assertThat(context.finish("SUCCEEDED", "3", null).modelCalls()).isEqualTo(2);
    }

    @Test
    void agentMessagesAreLocalEvenWhenParentBudgetIsShared() {
        List<ChatRequest> requests = new ArrayList<>();
        when(gateway.chat(any(ChatRequest.class), any(Duration.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return response(AiMessage.from(requests.size() == 1 ? "private-first-result" : "second-result"));
        });
        var context = new AgentExecutionContext(properties);
        agents.agent(AgentRole.CALCULATOR).execute(new AgentTask(1, "first-goal", "first", List.of()), context);
        agents.agent(AgentRole.TIME).execute(new AgentTask(2, "second-goal", "second", List.of()), context);
        assertThat(requests.get(1).messages().toString()).doesNotContain("private-first-result", "first-goal");
        assertThat(requests.get(1).toolSpecifications()).extracting(tool -> tool.name()).containsExactly("current_time");
        assertThat(context.finish("SUCCEEDED", "done", null).tokenUsage().totalTokens()).isEqualTo(30L);
    }

    @Test
    void taskAndEvidenceSnapshotsCannotBeChangedByAnotherAgent() {
        List<AgentResult> previous = new ArrayList<>();
        AgentTask task = new AgentTask(1, "goal", "task", previous);
        previous.add(AgentResult.of(task, AgentRole.ANALYST, "SUCCEEDED", "foreign", List.of()));
        assertThat(task.previousResults()).isEmpty();
        ObjectNode arguments = mapper.createObjectNode().put("left", 1);
        AgentEvidence evidence = new AgentEvidence("calculator", arguments, "3", true);
        arguments.put("left", 99);
        ((ObjectNode) evidence.arguments()).put("left", 100);
        assertThat(evidence.arguments().get("left").asInt()).isEqualTo(1);
        assertThatThrownBy(() -> task.previousResults().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void specialistImplementationCanReplaceGenericAgentAndServiceOnlyDelegates() {
        Map<AgentRole, RecordingAgent> team = team();
        var service = service(team);
        var result = service.execute(7L, new MultiAgentRunDTO("custom-goal"));
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.answer()).isEqualTo("reviewed-result");
        assertThat(team.get(AgentRole.COORDINATOR).tasks).hasSize(1);
        assertThat(team.get(AgentRole.CALCULATOR).tasks).hasSize(1);
        assertThat(team.get(AgentRole.ANALYST).tasks.get(0).previousResults().get(0).result()).isEqualTo("3");
        assertThat(team.get(AgentRole.REVIEWER).tasks.get(0).previousResults()).hasSize(2);
        assertThat(team.get(AgentRole.TIME).tasks).isEmpty();
        verify(gateway, never()).chat(any(ChatRequest.class), any(Duration.class));
    }

    @Test
    void failedChildStopsFurtherDelegationAndReview() {
        Map<AgentRole, RecordingAgent> team = team();
        team.get(AgentRole.CALCULATOR).behavior =
                (task, context) -> AgentResult.of(task, AgentRole.CALCULATOR, "FAILED", "child-failed", List.of());
        var result = service(team).execute(7L, new MultiAgentRunDTO("goal"));
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.stopReason()).isEqualTo("child-failed");
        assertThat(team.get(AgentRole.ANALYST).tasks).isEmpty();
        assertThat(team.get(AgentRole.REVIEWER).tasks).isEmpty();
    }

    @Test
    void foreignRoleResultCannotBeAcceptedAsChildDelivery() {
        Map<AgentRole, RecordingAgent> team = team();
        team.get(AgentRole.CALCULATOR).behavior =
                (task, context) -> AgentResult.of(task, AgentRole.TIME, "SUCCEEDED", "foreign", List.of());
        var result = service(team).execute(7L, new MultiAgentRunDTO("goal"));
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.stopReason()).contains("身份不匹配");
        assertThat(team.get(AgentRole.REVIEWER).tasks).isEmpty();
    }

    @Test
    void reviewerMustDeliverExplicitApprovalEvenWithSpecializedImplementation() {
        Map<AgentRole, RecordingAgent> team = team();
        team.get(AgentRole.REVIEWER).behavior =
                (task, context) -> AgentResult.of(task, AgentRole.REVIEWER, "SUCCEEDED", "raw-text", List.of());
        assertThat(service(team).execute(7L, new MultiAgentRunDTO("goal")).status()).isEqualTo("FAILED");
    }

    @Test
    void registrationRejectsDuplicateRoles() {
        Map<AgentRole, RecordingAgent> team = team();
        List<Agent> instances = new ArrayList<>(team.values());
        instances.add(team.get(AgentRole.CALCULATOR));
        assertThatThrownBy(() -> new AgentRegistry(instances)).isInstanceOf(IllegalStateException.class);
    }

    private MultiAgentServiceImpl service(Map<AgentRole, RecordingAgent> team) {
        return new MultiAgentServiceImpl(gateway, tools, properties,
                new AgentRegistry(new ArrayList<>(team.values())), new AiModelProperties());
    }

    private Map<AgentRole, RecordingAgent> team() {
        Map<AgentRole, RecordingAgent> team = new EnumMap<>(AgentRole.class);
        for (AgentRole role : AgentRole.values()) {
            team.put(role, new RecordingAgent(role));
        }
        team.get(AgentRole.COORDINATOR).behavior = (task, context) ->
                AgentResult.of(task, AgentRole.COORDINATOR, "SUCCEEDED", "assignments", List.of())
                        .withAssignments(List.of(new AgentAssignment(AgentRole.CALCULATOR, "calculate"),
                                new AgentAssignment(AgentRole.ANALYST, "analyze")));
        team.get(AgentRole.REVIEWER).behavior = (task, context) ->
                AgentResult.of(task, AgentRole.REVIEWER, "SUCCEEDED", "reviewed-result", List.of())
                        .withReview(new AgentReview(true, "reviewed-result", "checked"));
        return team;
    }

    private ChatResponse response(AiMessage message) {
        return ChatResponse.builder().aiMessage(message).tokenUsage(new TokenUsage(10, 5, 15)).build();
    }

    /** 与ConfiguredAgent不同的专业实现，用于证明协调服务依赖接口而非通用类。 */
    private static class RecordingAgent implements Agent {
        private final AgentRole role;
        private final List<AgentTask> tasks = new ArrayList<>();
        private BiFunction<AgentTask, AgentExecutionContext, AgentResult> behavior;

        RecordingAgent(AgentRole role) {
            this.role = role;
            behavior = (task, context) -> AgentResult.of(task, role, "SUCCEEDED", "3", List.of());
        }

        @Override
        public AgentRole role() {
            return role;
        }

        @Override
        public AgentResult execute(AgentTask task, AgentExecutionContext context) {
            tasks.add(task);
            return behavior.apply(task, context);
        }
    }
}
