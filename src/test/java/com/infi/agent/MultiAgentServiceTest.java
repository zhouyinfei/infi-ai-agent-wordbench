package com.infi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.common.BusinessException;
import com.infi.agent.config.AiModelProperties;
import com.infi.agent.config.MultiAgentProperties;
import com.infi.agent.dto.MultiAgentRunDTO;
import com.infi.agent.react.model.ReactModelGateway;
import com.infi.agent.react.tool.CalculatorTool;
import com.infi.agent.react.tool.CurrentTimeTool;
import com.infi.agent.react.tool.ReactToolRegistry;
import com.infi.agent.service.impl.MultiAgentServiceImpl;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 协调分工、角色隔离、工具权限、真实证据及共享预算的确定性验证。 */
class MultiAgentServiceTest {
    private ScriptedGateway gateway;
    private MultiAgentProperties properties;
    private MultiAgentServiceImpl service;

    @BeforeEach
    void setUp() {
        gateway = new ScriptedGateway();
        properties = new MultiAgentProperties();
        ObjectMapper mapper = new ObjectMapper();
        var registry = new ReactToolRegistry(List.of(new CalculatorTool(), new CurrentTimeTool(
                Clock.fixed(Instant.parse("2026-10-11T02:00:00Z"), ZoneOffset.UTC))), mapper);
        service = new MultiAgentServiceImpl(gateway, registry, properties, mapper, new AiModelProperties());
    }

    @Test
    void threeSpecialistsHandoffEvidenceAndIndependentReviewerApproves() {
        enqueue("{\"tasks\":[{\"agent\":\"CALCULATOR\",\"goal\":\"计算1加2\"},"
                + "{\"agent\":\"TIME\",\"goal\":\"查上海当前时间\"},"
                + "{\"agent\":\"ANALYST\",\"goal\":\"整合计算和时间结果\"}]}");
        gateway.enqueue(tool("calc", "calculator", "{\"left\":1,\"operator\":\"ADD\",\"right\":2}"));
        enqueue("计算结果3");
        gateway.enqueue(tool("time", "current_time", "{}"));
        enqueue("上海时间2026-10-11T10:00:00+08:00");
        enqueue("计算结果3，上海时间10点");
        enqueue(approved());
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.modelCalls()).isEqualTo(7);
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(result.tokenUsage().totalTokens()).isEqualTo(105L);
        assertThat(result.tokenUsage().complete()).isTrue();
        assertThat(gateway.requests.get(0).toolSpecifications()).isNullOrEmpty();
        assertThat(gateway.requests.get(1).toolSpecifications()).extracting(tool -> tool.name())
                .containsExactly("calculator");
        assertThat(gateway.requests.get(3).toolSpecifications()).extracting(tool -> tool.name())
                .containsExactly("current_time");
        assertThat(gateway.requests.get(5).toolSpecifications()).isNullOrEmpty();
        assertThat(gateway.requests.get(6).toolSpecifications()).isNullOrEmpty();
        assertThat(gateway.requests.get(3).messages()).noneMatch(ToolExecutionResultMessage.class::isInstance);
        assertThat(gateway.requests.get(3).messages().toString()).contains("计算结果3");
        assertThat(gateway.requests.get(6).messages().toString()).contains("evidence", "calculator", "current_time", "3");
        assertThat(result.steps()).extracting(step -> step.agentName())
                .contains("协调 Agent", "计算 Agent", "时间 Agent", "分析 Agent", "审校 Agent");
        assertThat(result.steps().stream().filter(step -> "AGENT".equals(step.type()))).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json", "{\"tasks\":[]}", "{\"tasks\":[{\"agent\":\"HACKER\",\"goal\":\"a\"}]}",
            "{\"tasks\":[{\"agent\":\"COORDINATOR\",\"goal\":\"a\"}]}",
            "{\"tasks\":[{\"agent\":\"REVIEWER\",\"goal\":\"a\"}]}",
            "{\"tasks\":[{\"agent\":\"ANALYST\",\"goal\":\" \"}]}",
            "{\"tasks\":[{\"agent\":\"ANALYST\",\"goal\":\"a\",\"tools\":[\"shell\"]}]}",
            "{\"tasks\":[{\"agent\":\"ANALYST\",\"goal\":\"a\"}]} {}"
    })
    void invalidOrRecursiveAssignmentsNeverExecute(String json) {
        enqueue(json);
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.modelCalls()).isEqualTo(1);
        assertThat(result.toolCalls()).isZero();
    }

    @Test
    void calculatorCannotExecuteTimeToolEvenIfModelRequestsIt() {
        singleTask("CALCULATOR");
        gateway.enqueue(tool("forbidden", "current_time", "{}"));
        enqueue("当前角色不能查询时间");
        enqueue("{\"approved\":false,\"answer\":\"\",\"reason\":\"缺少时间证据\"}");
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.steps()).anySatisfy(step -> {
            assertThat(step.toolName()).isEqualTo("current_time");
            assertThat(step.status()).isEqualTo("ERROR");
            assertThat(step.observation()).contains("授权范围");
        });
        assertThat(gateway.requests.get(2).messages()).anySatisfy(message ->
                assertThat(message).isInstanceOfSatisfying(ToolExecutionResultMessage.class,
                        observation -> assertThat(observation.isError()).isTrue()));
    }

    @Test
    void analystCannotExecuteTools() {
        singleTask("ANALYST");
        gateway.enqueue(tool("forbidden", "calculator", "{}"));
        enqueue("不能执行");
        enqueue("{\"approved\":false,\"answer\":\"\",\"reason\":\"未完成\"}");
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(gateway.requests.get(1).toolSpecifications()).isNullOrEmpty();
        assertThat(result.steps()).anySatisfy(step -> assertThat(step.observation()).contains("授权范围"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"approved\":false,\"answer\":\"\",\"reason\":\"结果矛盾\"}", "not-json",
            "{\"approved\":true,\"answer\":\"\",\"reason\":\"完成\"}",
            "{\"approved\":\"true\",\"answer\":\"a\",\"reason\":\"完成\"}"
    })
    void reviewMustBeValidAndApproved(String json) {
        singleTask("ANALYST");
        enqueue("专业结果");
        enqueue(json);
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.answer()).isNull();
        assertThat(result.steps()).anySatisfy(step -> assertThat(step.type()).isEqualTo("AGENT"));
    }

    @Test
    void coordinatorCannotCallTools() {
        gateway.enqueue(tool("one", "current_time", "{}"));
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.toolCalls()).isZero();
    }

    @Test
    void reviewerCannotCallTools() {
        singleTask("ANALYST");
        enqueue("完成");
        gateway.enqueue(tool("one", "current_time", "{}"));
        assertThat(service.execute(7L, goal()).status()).isEqualTo("FAILED");
    }

    @Test
    void totalModelBudgetIncludesCoordinatorWorkersAndReview() {
        properties.setMaxIterations(3);
        singleTask("CALCULATOR");
        gateway.enqueue(tool("calc", "calculator", "{\"left\":1,\"operator\":\"ADD\",\"right\":2}"));
        enqueue("结果3");
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("LIMIT_REACHED");
        assertThat(result.modelCalls()).isEqualTo(3);
        assertThat(result.tokenUsage().totalTokens()).isEqualTo(45L);
    }

    @Test
    void repetitionsAreLimitedAcrossAgentsTasks() {
        properties.setMaxRepeatedCalls(1);
        enqueue("{\"tasks\":[{\"agent\":\"TIME\",\"goal\":\"a\"},{\"agent\":\"TIME\",\"goal\":\"b\"}]}");
        gateway.enqueue(tool("one", "current_time", "{}"));
        enqueue("时间结果");
        gateway.enqueue(tool("two", "current_time", "{}"));
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("LIMIT_REACHED");
        assertThat(result.toolCalls()).isEqualTo(1);
    }

    @Test
    void totalToolBudgetRejectsOversizedBatch() {
        properties.setMaxToolCalls(1);
        singleTask("TIME");
        gateway.enqueue(AiMessage.from(List.of(
                ToolExecutionRequest.builder().id("one").name("current_time").arguments("{}").build(),
                ToolExecutionRequest.builder().id("two").name("current_time").arguments("{}").build())));
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("LIMIT_REACHED");
        assertThat(result.toolCalls()).isZero();
    }

    @Test
    void timeoutStopsBeforeAnyModelCall() {
        properties.setTaskTimeout(Duration.ofNanos(1));
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("TIMEOUT");
        assertThat(result.modelCalls()).isZero();
    }

    @Test
    void missingConfigurationAndProviderErrorsAreHandled() {
        gateway.configured = false;
        assertThatThrownBy(() -> service.execute(7L, goal())).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo("MODEL_NOT_CONFIGURED"));
        gateway.configured = true;
        gateway.failure = new IllegalStateException("secret-api-key");
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.stopReason()).doesNotContain("secret-api-key");
    }

    @Test
    void taskCountAndLengthAreBounded() {
        properties.setMaxTasks(1);
        enqueue("{\"tasks\":[{\"agent\":\"TIME\",\"goal\":\"a\"},{\"agent\":\"TIME\",\"goal\":\"b\"}]}");
        assertThat(service.execute(7L, goal()).status()).isEqualTo("FAILED");
        singleTask("ANALYST", "a".repeat(501));
        assertThat(service.execute(7L, goal()).status()).isEqualTo("FAILED");
    }

    @Test
    void independentExecutionsDoNotShareRoleMessagesOrTokenCounters() {
        singleTask("ANALYST");
        enqueue("first-private-result");
        enqueue(approved());
        var first = service.execute(7L, goal());
        singleTask("ANALYST");
        enqueue("second-result");
        enqueue(approved());
        var second = service.execute(8L, new MultiAgentRunDTO("另一个用户目标"));
        assertThat(first.runId()).isNotEqualTo(second.runId());
        assertThat(second.tokenUsage().totalTokens()).isEqualTo(45L);
        assertThat(gateway.requests.get(4).messages().toString()).doesNotContain("first-private-result");
    }

    private void singleTask(String agent) {
        singleTask(agent, "完成当前目标");
    }

    private void singleTask(String agent, String task) {
        enqueue("{\"tasks\":[{\"agent\":\"" + agent + "\",\"goal\":\"" + task + "\"}]}");
    }

    private void enqueue(String text) {
        gateway.enqueue(AiMessage.from(text));
    }

    private String approved() {
        return "{\"approved\":true,\"answer\":\"计算结果3，上海时间10点\",\"reason\":\"证据一致\"}";
    }

    private MultiAgentRunDTO goal() {
        return new MultiAgentRunDTO("计算1加2，并查询上海时间，整合结果");
    }

    private AiMessage tool(String id, String name, String arguments) {
        return AiMessage.from(ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build());
    }

    private static class ScriptedGateway implements ReactModelGateway {
        private final Queue<ChatResponse> responses = new ArrayDeque<>();
        private final List<ChatRequest> requests = new ArrayList<>();
        private boolean configured = true;
        private RuntimeException failure;

        void enqueue(AiMessage message) {
            responses.add(ChatResponse.builder().aiMessage(message).tokenUsage(new TokenUsage(10, 5, 15)).build());
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public ChatResponse chat(ChatRequest request, Duration remainingTime) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return responses.remove();
        }
    }
}
