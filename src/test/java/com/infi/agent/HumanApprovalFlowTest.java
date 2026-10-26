package com.infi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.common.BusinessException;
import com.infi.agent.config.AiModelProperties;
import com.infi.agent.config.MultiAgentProperties;
import com.infi.agent.config.ReactProperties;
import com.infi.agent.dto.AgentTaskDecisionDTO;
import com.infi.agent.dto.AgentTaskSubmitDTO;
import com.infi.agent.memory.ConversationMode;
import com.infi.agent.multi.AgentRegistry;
import com.infi.agent.multi.DefaultAgentExecutor;
import com.infi.agent.react.model.ReactModelGateway;
import com.infi.agent.react.tool.ReactTool;
import com.infi.agent.react.tool.ReactToolRegistry;
import com.infi.agent.react.tool.CalculatorTool;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.service.ConversationService;
import com.infi.agent.service.impl.AgentTaskServiceImpl;
import com.infi.agent.service.impl.MultiAgentServiceImpl;
import com.infi.agent.service.impl.PlanExecuteServiceImpl;
import com.infi.agent.service.impl.ReactServiceImpl;
import com.infi.agent.vo.AgentTaskVO;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 使用真实三种编排器验证模型触发、本人确认、原地继续和拒绝时零副作用。 */
class HumanApprovalFlowTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UserPrincipal owner = new UserPrincipal(1L, "owner", "USER");
    private final TrackingDeleteTool tool = new TrackingDeleteTool();
    private ReactModelGateway gateway;
    private AgentTaskServiceImpl tasks;
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        gateway = mock(ReactModelGateway.class);
        when(gateway.isConfigured()).thenReturn(true);
        ReactToolRegistry registry = new ReactToolRegistry(List.of(tool, new CalculatorTool()), mapper);
        ReactProperties properties = new ReactProperties();
        MultiAgentProperties multiProperties = new MultiAgentProperties();
        AiModelProperties models = new AiModelProperties();
        var react = new ReactServiceImpl(gateway, registry, properties, models);
        var plan = new PlanExecuteServiceImpl(gateway, registry, properties, mapper);
        var agents = new AgentRegistry(new DefaultAgentExecutor(gateway, registry, mapper), mapper);
        var multi = new MultiAgentServiceImpl(gateway, registry, multiProperties, agents, models);
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(2);
        executor.initialize();
        tasks = new AgentTaskServiceImpl(executor, mock(ConversationService.class), react, plan, multi);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @ParameterizedTest
    @EnumSource(ConversationMode.class)
    void modelRequestsCurrentUserApprovalAndContinuesSameTask(ConversationMode mode) throws Exception {
        prepare(mode);
        AgentTaskVO task = submit(mode);
        AgentTaskVO waiting = await(task.id(), true);
        assertThat(waiting.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(waiting.approval().toolName()).isEqualTo("file_delete");
        assertThat(waiting.approval().arguments()).isEqualTo("{\"path\":\"sample.txt\"}");
        assertThat(tool.executions.get()).isZero();
        var stranger = new UserPrincipal(2L, "administrator", "ADMIN");
        var decision = new AgentTaskDecisionDTO(waiting.approval().id(), true);
        assertThatThrownBy(() -> tasks.get(stranger, task.id())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> tasks.decide(stranger, task.id(), decision)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> tasks.decide(owner, task.id(), new AgentTaskDecisionDTO("stale", true)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> submit(mode)).isInstanceOf(BusinessException.class);
        tasks.decide(owner, task.id(), decision);
        AgentTaskVO finished = await(task.id(), false);
        assertThat(finished.result().status()).isEqualTo("SUCCEEDED");
        assertThat(tool.executions.get()).isEqualTo(1);
        assertThat(tool.lastPath).isEqualTo("sample.txt");
        assertThat(finished.result().steps()).anyMatch(step -> "APPROVAL".equals(step.type())
                && "APPROVED".equals(step.status()));
        assertThatThrownBy(() -> tasks.decide(owner, task.id(), decision)).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @EnumSource(ConversationMode.class)
    void rejectingStopsAllModesBeforeAnyTargetToolRuns(ConversationMode mode) throws Exception {
        prepare(mode);
        AgentTaskVO task = submit(mode);
        AgentTaskVO waiting = await(task.id(), true);
        tasks.decide(owner, task.id(), new AgentTaskDecisionDTO(waiting.approval().id(), false));
        AgentTaskVO finished = await(task.id(), false);
        assertThat(finished.result().status()).isEqualTo("REJECTED");
        assertThat(tool.executions.get()).isZero();
        assertThat(finished.result().steps()).anyMatch(step -> "APPROVAL".equals(step.type())
                && "REJECTED".equals(step.status()));
        verify(gateway, times(mode == ConversationMode.REACT ? 1 : 2)).chat(any(ChatRequest.class), any(Duration.class));
    }

    @Test
    void ordinaryAnswerDoesNotRequireManualApproval() throws Exception {
        when(gateway.chat(any(ChatRequest.class), any(Duration.class)))
                .thenReturn(response(AiMessage.from("你好")));
        AgentTaskVO task = submit(ConversationMode.REACT);
        assertThat(await(task.id(), false).result().answer()).isEqualTo("你好");
        assertThat(tool.executions.get()).isZero();
    }

    @Test
    void approvingLaterStepDoesNotReplayEarlierSideEffects() throws Exception {
        String arguments = mapper.writeValueAsString(java.util.Map.of("reason", "请确认第二项删除",
                "toolName", "file_delete", "toolArguments", "{\"path\":\"second.txt\"}"));
        when(gateway.chat(any(ChatRequest.class), any(Duration.class))).thenReturn(
                response(AiMessage.from(ToolExecutionRequest.builder().id("first")
                        .name("file_delete").arguments("{\"path\":\"first.txt\"}").build())),
                response(AiMessage.from(ToolExecutionRequest.builder().id("second")
                        .name("request_approval").arguments(arguments).build())),
                response(AiMessage.from("完成")));
        AgentTaskVO task = submit(ConversationMode.REACT);
        AgentTaskVO waiting = await(task.id(), true);
        assertThat(tool.executions.get()).isEqualTo(1);
        tasks.decide(owner, task.id(), new AgentTaskDecisionDTO(waiting.approval().id(), true));
        assertThat(await(task.id(), false).result().status()).isEqualTo("SUCCEEDED");
        assertThat(tool.executions.get()).isEqualTo(2);
        assertThat(tool.lastPath).isEqualTo("second.txt");
        verify(gateway, times(3)).chat(any(ChatRequest.class), any(Duration.class));
    }

    @Test
    void multiAgentCannotUseApprovalToBypassItsToolPermissions() throws Exception {
        String arguments = mapper.writeValueAsString(java.util.Map.of("reason", "请批准计算",
                "toolName", "calculator", "toolArguments", "{\"left\":1,\"operator\":\"ADD\",\"right\":2}"));
        when(gateway.chat(any(ChatRequest.class), any(Duration.class))).thenReturn(
                response(AiMessage.from("{\"tasks\":[{\"agent\":\"FILE\",\"goal\":\"处理文件\"}]}")),
                response(AiMessage.from(ToolExecutionRequest.builder().id("forbidden")
                        .name("request_approval").arguments(arguments).build())),
                response(AiMessage.from("无法调用越权工具")),
                response(AiMessage.from("{\"approved\":false,\"answer\":\"\",\"reason\":\"任务未完成\"}")));
        AgentTaskVO task = submit(ConversationMode.MULTI_AGENT);
        AgentTaskVO finished = await(task.id(), false);
        assertThat(finished.result().status()).isEqualTo("FAILED");
        assertThat(finished.result().steps()).anyMatch(step -> "工具不在当前Agent的授权范围内".equals(step.observation()));
        assertThat(finished.result().steps()).noneMatch(step -> "APPROVAL".equals(step.type()));
        assertThat(tool.executions.get()).isZero();
    }

    @Test
    void mixedApprovalBatchCannotRunTargetBeforeConfirmation() throws Exception {
        String arguments = mapper.writeValueAsString(java.util.Map.of("reason", "请确认删除",
                "toolName", "file_delete", "toolArguments", "{\"path\":\"sample.txt\"}"));
        when(gateway.chat(any(ChatRequest.class), any(Duration.class))).thenReturn(
                response(AiMessage.from(List.of(
                        ToolExecutionRequest.builder().id("direct").name("file_delete")
                                .arguments("{\"path\":\"sample.txt\"}").build(),
                        ToolExecutionRequest.builder().id("approval").name("request_approval")
                                .arguments(arguments).build()))),
                response(AiMessage.from("本批次没有执行任何操作")));
        AgentTaskVO task = submit(ConversationMode.REACT);
        AgentTaskVO finished = await(task.id(), false);
        assertThat(tool.executions.get()).isZero();
        assertThat(finished.result().steps().stream().filter(step -> "TOOL".equals(step.type())))
                .allMatch(step -> "ERROR".equals(step.status()));
    }

    private AgentTaskVO submit(ConversationMode mode) {
        return tasks.submit(owner, new AgentTaskSubmitDTO("删除sample.txt，执行前请让我确认", mode,
                null, false, false, null));
    }

    private void prepare(ConversationMode mode) throws Exception {
        List<ChatResponse> responses = new ArrayList<>();
        if (mode == ConversationMode.PLAN_EXECUTE) {
            responses.add(response(AiMessage.from("{\"steps\":[\"删除指定文件\"]}")));
        } else if (mode == ConversationMode.MULTI_AGENT) {
            responses.add(response(AiMessage.from("{\"tasks\":[{\"agent\":\"FILE\",\"goal\":\"删除指定文件\"}]}")));
        }
        String arguments = mapper.writeValueAsString(java.util.Map.of("reason", "删除不可恢复，需要你确认",
                "toolName", "file_delete", "toolArguments", "{\"path\":\"sample.txt\"}"));
        responses.add(response(AiMessage.from(ToolExecutionRequest.builder().id("approval-call")
                .name("request_approval").arguments(arguments).build())));
        responses.add(response(AiMessage.from("文件已删除")));
        if (mode == ConversationMode.PLAN_EXECUTE) {
            responses.add(response(AiMessage.from("任务完成")));
        } else if (mode == ConversationMode.MULTI_AGENT) {
            responses.add(response(AiMessage.from("{\"approved\":true,\"answer\":\"任务完成\",\"reason\":\"工具证据有效\"}")));
        }
        AtomicInteger index = new AtomicInteger();
        when(gateway.chat(any(ChatRequest.class), any(Duration.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
                assertThat(request.toolSpecifications()).anyMatch(spec -> "request_approval".equals(spec.name()));
            }
            return responses.get(index.getAndIncrement());
        });
    }

    private AgentTaskVO await(String id, boolean pending) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            AgentTaskVO snapshot = tasks.get(owner, id);
            if (pending ? snapshot.approval() != null : snapshot.result() != null) {
                return snapshot;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("任务没有到达预期状态：" + tasks.get(owner, id));
    }

    private ChatResponse response(AiMessage message) {
        return ChatResponse.builder().aiMessage(message).build();
    }

    public static class TrackingDeleteTool implements ReactTool {
        private final AtomicInteger executions = new AtomicInteger();
        private volatile String lastPath;

        @Override
        public String execute(JsonNode arguments) {
            lastPath = arguments.path("path").asText();
            executions.incrementAndGet();
            return "删除完成";
        }

        @Tool(name = "file_delete", value = "删除指定文件")
        public String delete(String path) {
            return path;
        }
    }
}
