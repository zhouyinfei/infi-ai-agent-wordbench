package com.infi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.common.BusinessException;
import com.infi.agent.config.ReactProperties;
import com.infi.agent.dto.PlanExecuteRunDTO;
import com.infi.agent.react.model.ReactModelGateway;
import com.infi.agent.react.tool.CalculatorTool;
import com.infi.agent.react.tool.CurrentTimeTool;
import com.infi.agent.react.tool.ReactToolRegistry;
import com.infi.agent.service.impl.PlanExecuteServiceImpl;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.time.Clock;
import java.time.Duration;
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

/** 验证先规划再执行、结果传递和跨步骤共享预算。 */
class PlanExecuteServiceTest {
    private ScriptedGateway gateway;
    private ReactProperties properties;
    private PlanExecuteServiceImpl service;

    @BeforeEach
    void setUp() {
        gateway = new ScriptedGateway();
        properties = new ReactProperties();
        ObjectMapper mapper = new ObjectMapper();
        service = new PlanExecuteServiceImpl(gateway, new ReactToolRegistry(
                List.of(new CalculatorTool(), new CurrentTimeTool(Clock.systemUTC())), mapper), properties, mapper);
    }

    @Test
    void plansExecutesTwoDependentStepsAndSummarizes() {
        gateway.enqueue(AiMessage.from("{\"steps\":[\"计算128加256\",\"把第一步结果乘以3\"]}"));
        gateway.enqueue(tool("first", "calculator", "{\"left\":128,\"operator\":\"ADD\",\"right\":256}"));
        gateway.enqueue(AiMessage.from("第一步结果是384"));
        gateway.enqueue(tool("second", "calculator", "{\"left\":384,\"operator\":\"MULTIPLY\",\"right\":3}"));
        gateway.enqueue(AiMessage.from("第二步结果是1152"));
        gateway.enqueue(AiMessage.from("最终结果是1152"));
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.modelCalls()).isEqualTo(6);
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(result.answer()).contains("1152");
        assertThat(result.tokenUsage().inputTokens()).isEqualTo(60L);
        assertThat(result.tokenUsage().outputTokens()).isEqualTo(30L);
        assertThat(result.tokenUsage().totalTokens()).isEqualTo(90L);
        assertThat(result.tokenUsage().reportedModelCalls()).isEqualTo(6);
        assertThat(result.tokenUsage().complete()).isTrue();
        assertThat(gateway.requests.get(0).toolSpecifications()).isNullOrEmpty();
        assertThat(gateway.requests.get(1).toolSpecifications()).hasSize(2);
        assertThat(gateway.requests.get(2).messages()).anySatisfy(message ->
                assertThat(message).isInstanceOfSatisfying(ToolExecutionResultMessage.class,
                        observation -> assertThat(observation.text()).isEqualTo("384")));
        assertThat(gateway.requests.get(3).messages().toString()).contains("第一步结果是384");
        assertThat(gateway.requests.get(5).messages().toString()).contains("第一步结果是384", "第二步结果是1152");
        assertThat(gateway.requests.get(5).toolSpecifications()).isNullOrEmpty();
        assertThat(result.steps().stream().filter(step -> "STEP".equals(step.type()))).hasSize(2);
        assertThat(result.steps()).anySatisfy(step -> {
            assertThat(step.summary()).isEqualTo("执行计划");
            assertThat(step.arguments().size()).isEqualTo(2);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json", "{}", "{\"steps\":[]}", "{\"steps\":[\"\"]}", "{\"steps\":[123]}",
            "{\"steps\":[\"a\"],\"extra\":true}", "{\"steps\":[\"a\"]} {}",
            "{\"steps\":[\"a\",\"b\",\"c\",\"d\",\"e\"]}"
    })
    void malformedPlanStopsBeforeExecutingAnyStep(String plan) {
        gateway.enqueue(AiMessage.from(plan));
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.modelCalls()).isEqualTo(1);
        assertThat(result.toolCalls()).isZero();
        assertThat(gateway.requests).hasSize(1);
    }

    @Test
    void longPlanStepIsRejected() {
        gateway.enqueue(AiMessage.from("{\"steps\":[\"" + "a".repeat(501) + "\"]}"));
        assertThat(service.execute(7L, goal()).status()).isEqualTo("FAILED");
    }

    @Test
    void simpleTaskCanCompleteWithoutTools() {
        enqueueSingleStep();
        gateway.enqueue(AiMessage.from("解释完成"));
        gateway.enqueue(AiMessage.from("最终解释"));
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.modelCalls()).isEqualTo(3);
        assertThat(result.toolCalls()).isZero();
    }

    @Test
    void modelBudgetIsSharedByPlanningExecutionAndSummary() {
        properties.setMaxIterations(3);
        enqueueSingleStep();
        gateway.enqueue(tool("one", "calculator", "{\"left\":1,\"operator\":\"ADD\",\"right\":2}"));
        gateway.enqueue(AiMessage.from("结果3"));
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("LIMIT_REACHED");
        assertThat(result.modelCalls()).isEqualTo(3);
        assertThat(result.answer()).isNull();
        assertThat(result.tokenUsage().totalTokens()).isEqualTo(45L);
        assertThat(result.steps()).anySatisfy(step -> assertThat(step.type()).isEqualTo("STEP"));
    }

    @Test
    void toolBudgetIsCheckedBeforeExecutingBatch() {
        properties.setMaxToolCalls(1);
        enqueueSingleStep();
        gateway.enqueue(AiMessage.from(List.of(
                ToolExecutionRequest.builder().id("one").name("current_time").arguments("{}").build(),
                ToolExecutionRequest.builder().id("two").name("current_time").arguments("{}").build())));
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("LIMIT_REACHED");
        assertThat(result.toolCalls()).isZero();
    }

    @Test
    void repeatedCallsAreCountedAcrossPlanSteps() {
        properties.setMaxRepeatedCalls(1);
        gateway.enqueue(AiMessage.from("{\"steps\":[\"查时间\",\"再查时间\"]}"));
        gateway.enqueue(tool("one", "current_time", "{}"));
        gateway.enqueue(AiMessage.from("第一步完成"));
        gateway.enqueue(tool("two", "current_time", "{}"));
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("LIMIT_REACHED");
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.stopReason()).contains("重复");
    }

    @Test
    void errorsAreObservedAndCanBeCorrected() {
        enqueueSingleStep();
        gateway.enqueue(tool("one", "calculator", "{\"left\":1,\"operator\":\"DIVIDE\",\"right\":0}"));
        gateway.enqueue(tool("two", "calculator", "{\"left\":1,\"operator\":\"ADD\",\"right\":2}"));
        gateway.enqueue(AiMessage.from("结果3"));
        gateway.enqueue(AiMessage.from("结果3"));
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.steps()).anySatisfy(step -> {
            assertThat(step.status()).isEqualTo("ERROR");
            assertThat(step.observation()).contains("除数不能为零");
        });
        assertThat(gateway.requests.get(2).messages()).anySatisfy(message ->
                assertThat(message).isInstanceOfSatisfying(ToolExecutionResultMessage.class,
                        observation -> assertThat(observation.isError()).isTrue()));
    }

    @Test
    void unregisteredToolNeverExecutes() {
        enqueueSingleStep();
        gateway.enqueue(tool("one", "shell", "{\"command\":\"whoami\"}"));
        gateway.enqueue(AiMessage.from("该工具不可用"));
        gateway.enqueue(AiMessage.from("工具不可用，无法完成"));
        var result = service.execute(7L, goal());
        assertThat(result.steps()).anySatisfy(step -> {
            assertThat(step.status()).isEqualTo("ERROR");
            assertThat(step.observation()).contains("注册白名单");
        });
    }

    @Test
    void plannerCannotExecuteTools() {
        gateway.enqueue(tool("one", "current_time", "{}"));
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.toolCalls()).isZero();
    }

    @Test
    void summaryCannotExecuteTools() {
        enqueueSingleStep();
        gateway.enqueue(AiMessage.from("步骤完成"));
        gateway.enqueue(tool("one", "current_time", "{}"));
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.toolCalls()).isZero();
    }

    @Test
    void timeoutStopsBeforeRequest() {
        properties.setTaskTimeout(Duration.ofNanos(1));
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("TIMEOUT");
        assertThat(result.modelCalls()).isZero();
    }

    @Test
    void modelErrorsDoNotExposeProviderDetails() {
        gateway.failure = new IllegalStateException("secret-key-provider-response");
        var result = service.execute(7L, goal());
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.stopReason()).doesNotContain("secret-key");
    }

    @Test
    void missingModelIsRejected() {
        gateway.configured = false;
        assertThatThrownBy(() -> service.execute(7L, goal())).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("MODEL_NOT_CONFIGURED"));
    }

    @Test
    void truncatedPlanIsRejected() {
        gateway.responses.add(ChatResponse.builder().aiMessage(AiMessage.from("{\"steps\":[\"a\"]}"))
                .finishReason(FinishReason.LENGTH).build());
        assertThat(service.execute(7L, goal()).status()).isEqualTo("FAILED");
    }

    private void enqueueSingleStep() {
        gateway.enqueue(AiMessage.from("{\"steps\":[\"完成当前任务\"]}"));
    }

    private PlanExecuteRunDTO goal() {
        return new PlanExecuteRunDTO("完成任务");
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
