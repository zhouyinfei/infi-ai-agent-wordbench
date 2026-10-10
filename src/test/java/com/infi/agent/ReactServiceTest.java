package com.infi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.common.BusinessException;
import com.infi.agent.config.AiModelProperties;
import com.infi.agent.config.ReactProperties;
import com.infi.agent.dto.ReactRunDTO;
import com.infi.agent.react.model.ReactModelGateway;
import com.infi.agent.react.tool.CalculatorTool;
import com.infi.agent.react.tool.CurrentTimeTool;
import com.infi.agent.react.tool.ReactToolRegistry;
import com.infi.agent.service.impl.ReactServiceImpl;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 确定性验证多轮动作、观察反馈、授权和执行边界。 */
class ReactServiceTest {
    private ScriptedGateway gateway;
    private ReactProperties properties;
    private ReactServiceImpl service;
    private ReactToolRegistry registry;

    @BeforeEach
    void setUp() {
        gateway = new ScriptedGateway();
        properties = new ReactProperties();
        registry = new ReactToolRegistry(List.of(new CalculatorTool(), new CurrentTimeTool(
                Clock.fixed(Instant.parse("2026-10-10T02:00:00Z"), ZoneOffset.UTC))), new ObjectMapper());
        service = new ReactServiceImpl(gateway, registry, properties, new AiModelProperties());
    }

    @Test
    void performsTwoDependentToolRoundsAndReturnsRealObservations() {
        gateway.enqueue(tool("first", "calculator", "{\"left\":128,\"operator\":\"ADD\",\"right\":256}"));
        gateway.enqueue(tool("second", "calculator", "{\"left\":384,\"operator\":\"MULTIPLY\",\"right\":3}"));
        gateway.enqueue(AiMessage.from("结果是1152。"));
        var result = service.execute(1L, goal());
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.modelCalls()).isEqualTo(3);
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(result.answer()).contains("1152");
        assertThat(result.steps()).hasSize(5);
        assertThat(gateway.requests.get(1).messages()).anySatisfy(message -> {
            assertThat(message).isInstanceOf(ToolExecutionResultMessage.class);
            assertThat(((ToolExecutionResultMessage) message).text()).isEqualTo("384");
        });
        assertThat(gateway.requests.get(2).messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(message -> ((ToolExecutionResultMessage) message).text())).containsExactly("384", "1152");
    }

    @Test
    void directAnswerDoesNotExecuteTools() {
        gateway.enqueue(AiMessage.from("ReAct通过动作与观察迭代完成任务。"));
        var result = service.execute(1L, goal());
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.toolCalls()).isZero();
    }

    @Test
    void toolErrorCanBeCorrectedInFollowingRound() {
        gateway.enqueue(tool("one", "calculator", "{\"left\":1,\"operator\":\"DIVIDE\",\"right\":0}"));
        gateway.enqueue(tool("two", "calculator", "{\"left\":1,\"operator\":\"DIVIDE\",\"right\":2}"));
        gateway.enqueue(AiMessage.from("修正后结果是0.5"));
        var result = service.execute(1L, goal());
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.steps()).anySatisfy(step -> {
            assertThat(step.type()).isEqualTo("TOOL");
            assertThat(step.status()).isEqualTo("ERROR");
            assertThat(step.observation()).contains("除数");
        });
        assertThat(gateway.requests.get(1).messages().get(3))
                .isInstanceOfSatisfying(ToolExecutionResultMessage.class, message ->
                        assertThat(message.isError()).isTrue());
    }

    @Test
    void unauthorizedToolIsNotExecuted() {
        gateway.enqueue(tool("one", "current_time", "{}"));
        gateway.enqueue(AiMessage.from("没有时间工具权限。"));
        var result = service.execute(1L, new ReactRunDTO("查询时间", List.of("calculator")));
        assertThat(result.steps()).anySatisfy(step -> {
            assertThat(step.status()).isEqualTo("ERROR");
            assertThat(step.observation()).contains("白名单");
        });
        assertThat(gateway.requests.get(0).toolSpecifications()).extracting(specification -> specification.name())
                .containsExactly("calculator");
    }

    @Test
    void malformedArgumentsAreFeedbackRatherThanExecuted() {
        gateway.enqueue(tool("one", "calculator", "not-json"));
        gateway.enqueue(AiMessage.from("参数错误"));
        var result = service.execute(1L, goal());
        assertThat(result.steps()).anySatisfy(step -> assertThat(step.observation()).contains("合法JSON"));
    }

    @Test
    void repeatedCallsStopWithoutExecutingThirdAttempt() {
        for (int index = 0; index < 3; index++) {
            gateway.enqueue(tool("call-" + index, "current_time", "{}"));
        }
        var result = service.execute(1L, goal());
        assertThat(result.status()).isEqualTo("LIMIT_REACHED");
        assertThat(result.stopReason()).contains("重复");
        assertThat(result.toolCalls()).isEqualTo(2);
    }

    @Test
    void iterationLimitStopsExecution() {
        properties.setMaxIterations(1);
        gateway.enqueue(tool("one", "current_time", "{}"));
        var result = service.execute(1L, goal());
        assertThat(result.status()).isEqualTo("LIMIT_REACHED");
        assertThat(result.stopReason()).contains("迭代");
        assertThat(result.modelCalls()).isEqualTo(1);
    }

    @Test
    void toolBudgetChecksWholeBatchBeforeExecuting() {
        properties.setMaxToolCalls(1);
        gateway.enqueue(AiMessage.from(List.of(
                ToolExecutionRequest.builder().id("one").name("current_time").arguments("{}").build(),
                ToolExecutionRequest.builder().id("two").name("current_time").arguments("{}").build())));
        var result = service.execute(1L, goal());
        assertThat(result.status()).isEqualTo("LIMIT_REACHED");
        assertThat(result.toolCalls()).isZero();
    }

    @Test
    void providerFailureReturnsSafeMessageAndPartialTrace() {
        gateway.failure = new IllegalStateException("private-api-key");
        var result = service.execute(1L, goal());
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.stopReason()).doesNotContain("private-api-key");
        assertThat(result.steps()).hasSize(1);
    }

    @Test
    void deadlineStopsBeforeCallingModel() {
        properties.setTaskTimeout(Duration.ofNanos(1));
        var result = service.execute(1L, goal());
        assertThat(result.status()).isEqualTo("TIMEOUT");
        assertThat(result.modelCalls()).isZero();
    }

    @Test
    void missingConfigurationAndInvalidWhitelistAreRejected() {
        gateway.configured = false;
        assertThatThrownBy(() -> service.execute(1L, goal()))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("MODEL_NOT_CONFIGURED"));
        assertThatThrownBy(() -> service.execute(1L, new ReactRunDTO("目标", List.of("shell"))))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("INVALID_TOOLS"));
    }

    @Test
    void emptyAndTruncatedResponsesCannotCountAsSuccess() {
        gateway.responses.add(ChatResponse.builder().aiMessage(AiMessage.from("未完成"))
                .finishReason(FinishReason.LENGTH).build());
        assertThat(service.execute(1L, goal()).status()).isEqualTo("FAILED");
        gateway.responses.add(ChatResponse.builder().aiMessage(AiMessage.from(" ")).build());
        assertThat(service.execute(1L, goal()).status()).isEqualTo("FAILED");
    }

    @Test
    void builtInToolsRejectInvalidDataAndReturnDeterministicTime() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var observation = registry.execute(ToolExecutionRequest.builder().id("one").name("current_time")
                .arguments("{}").build(), registry.names());
        assertThat(observation.result()).isEqualTo("2026-10-10T10:00:00+08:00[Asia/Shanghai]");
        assertThat(registry.execute(ToolExecutionRequest.builder().id("two").name("current_time")
                .arguments("{\"timezone\":\"not-a-zone\"}").build(), registry.names()).success()).isFalse();
        CalculatorTool calculator = new CalculatorTool();
        assertThat(calculator.execute(mapper.readTree("{\"left\":0.1,\"operator\":\"ADD\",\"right\":0.2}")))
                .isEqualTo("0.3");
        assertThatThrownBy(() -> calculator.execute(mapper.readTree(
                "{\"left\":1e30,\"operator\":\"MULTIPLY\",\"right\":2}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.execute(mapper.readTree(
                "{\"left\":1,\"operator\":\"EVAL\",\"right\":2}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void precisionAndTrailingJsonAreValidatedAtToolBoundary() {
        var precise = registry.execute(ToolExecutionRequest.builder().id("precise").name("calculator")
                .arguments("{\"left\":0.123456789012345678901,\"operator\":\"ADD\",\"right\":0.000000000000000000001}")
                .build(), registry.names());
        assertThat(precise.result()).isEqualTo("0.123456789012345678902");
        var trailing = registry.execute(ToolExecutionRequest.builder().id("trailing").name("current_time")
                .arguments("{} {}").build(), registry.names());
        assertThat(trailing.success()).isFalse();
    }

    private ReactRunDTO goal() {
        return new ReactRunDTO("完成测试任务", null);
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
            responses.add(ChatResponse.builder().aiMessage(message).build());
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
