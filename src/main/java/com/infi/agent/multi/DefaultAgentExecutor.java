package com.infi.agent.multi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.approval.HumanApprovalContext;
import com.infi.agent.react.model.ReactModelGateway;
import com.infi.agent.react.tool.ReactToolRegistry;
import com.infi.agent.vo.ReactStepVO;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 无状态的通用执行器；每次调用创建独立消息和证据列表，不保存当前角色。 */
@Component
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class DefaultAgentExecutor {
    private static final int MAX_OUTPUT_TOKENS = 1024;
    private final ReactModelGateway modelGateway;
    private final ReactToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    /**
     * 使用实例的固定职责执行子任务，自主管理工具循环并返回交付状态。
     *
     * @param task 子任务及此前交付结果
     * @param definition 当前Agent实例的固定配置
     * @param context 父执行共享预算
     * @return 完成结果或停止状态，附真实工具证据
     */
    public AgentResult execute(AgentTask task, AgentDefinition definition, AgentExecutionContext context) {
        List<ChatMessage> messages = new ArrayList<>();
        List<AgentEvidence> evidence = new ArrayList<>();
        Invocation invocation = new Invocation(task, definition, context, messages, evidence);
        messages.add(SystemMessage.from(definition.systemPrompt() + "\n" + HumanApprovalContext.instruction()
                + "\n" + context.knowledgeScope().selectionInstruction()));
        messages.addAll(task.conversationHistory());
        messages.add(UserMessage.from("任务数据：" + objectMapper.valueToTree(task)));
        try {
            while (true) {
                AiMessage answer = ask(invocation);
                if (!answer.hasToolExecutionRequests()) {
                    if (answer.text() == null || answer.text().isBlank()) {
                        throw new AgentExecutionException("FAILED", "模型未返回有效阶段结果");
                    }
                    return AgentResult.of(task, definition.role(), "SUCCEEDED", answer.text(), evidence);
                }
                messages.add(answer);
                executeTools(answer.toolExecutionRequests(), invocation);
            }
        } catch (AgentExecutionException exception) {
            log.warn("Agent stopped, runId={}, agent={}, taskNumber={}, status={}, reason={}",
                    org.slf4j.MDC.get("runId"), definition.role(), task.taskNumber(), exception.status(), exception.getMessage());
            return AgentResult.of(task, definition.role(), exception.status(), exception.getMessage(), evidence);
        }
    }

    /**
     * 发起当前Agent的模型请求；工具定义来自固定角色权限，不由任务数据改变。
     *
     * @param invocation 独立的单次调用状态
     * @return 已校验的模型回复
     */
    private AiMessage ask(Invocation invocation) {
        Duration remaining = invocation.context().beginModelCall();
        long started = System.nanoTime();
        ChatRequest.Builder builder = ChatRequest.builder().messages(List.copyOf(invocation.messages()))
                .maxOutputTokens(MAX_OUTPUT_TOKENS);
        if (invocation.definition().withTools()) {
            builder.toolSpecifications(toolRegistry.specifications(invocation.context().knowledgeScope()).stream()
                    .filter(tool -> invocation.definition().role().allowsTool(tool.name())).toList());
        }
        ChatResponse response;
        log.info("Agent model started, runId={}, agent={}, taskNumber={}, remainingMs={}, maxOutputTokens={}",
                org.slf4j.MDC.get("runId"), invocation.definition().role(), invocation.task().taskNumber(),
                remaining.toMillis(), MAX_OUTPUT_TOKENS);
        try {
            response = modelGateway.chat(builder.build(), remaining);
            invocation.context().reportUsage(response == null ? null : response.tokenUsage());
        } catch (RuntimeException exception) {
            log.error("Agent model failed, runId={}, agent={}, taskNumber={}, durationMs={}",
                    org.slf4j.MDC.get("runId"), invocation.definition().role(), invocation.task().taskNumber(),
                    elapsed(started), exception);
            trace(invocation, invocation.definition().traceType(), "ERROR", "模型调用失败", elapsed(started));
            invocation.context().remaining();
            throw new AgentExecutionException("FAILED", "模型调用失败，请检查模型配置和服务状态");
        }
        invocation.context().remaining();
        AiMessage answer = response == null ? null : response.aiMessage();
        log.info("Agent model completed, runId={}, agent={}, taskNumber={}, durationMs={}, finishReason={}, tokenUsage={}, emptyMessage={}, textLength={}, toolRequests={}",
                org.slf4j.MDC.get("runId"), invocation.definition().role(), invocation.task().taskNumber(), elapsed(started),
                response == null ? null : response.finishReason(), response == null ? null : response.tokenUsage(),
                answer == null, answer == null || answer.text() == null ? 0 : answer.text().length(),
                answer != null && answer.hasToolExecutionRequests() ? answer.toolExecutionRequests().size() : 0);
        if (answer == null || response.finishReason() == FinishReason.LENGTH) {
            throw new AgentExecutionException("FAILED", "模型返回为空或输出被截断");
        }
        if (!invocation.definition().withTools() && answer.hasToolExecutionRequests()) {
            throw new AgentExecutionException("FAILED", "协调或审校阶段不允许调用工具");
        }
        trace(invocation, invocation.definition().traceType(), "SUCCESS",
                answer.hasToolExecutionRequests() ? "模型选择工具调用" : "模型返回阶段结果", elapsed(started));
        return answer;
    }

    /**
     * 检查请求结构及批次预算后，并行执行独立只读工具，按请求顺序反馈观察结果。
     *
     * @param calls 本轮模型提出的工具请求
     * @param invocation 当前Agent的消息、证据及父预算
     */
    private void executeTools(List<ToolExecutionRequest> calls, Invocation invocation) {
        if (calls.stream().anyMatch(call -> call == null || call.name() == null || call.id() == null
                || call.id().isBlank())
                || calls.stream().map(ToolExecutionRequest::id).distinct().count() != calls.size()) {
            throw new AgentExecutionException("FAILED", "模型返回的工具调用结构不合法");
        }
        invocation.context().checkToolCapacity(calls.size());
        for (ToolExecutionRequest call : calls) {
            invocation.context().beginToolCall(call);
        }
        // 未授权请求不进入执行池，仍保留对应的错误观察。
        var allowed = calls.stream().filter(call -> invocation.definition().role()
                .allowsTool(toolRegistry.effectiveToolName(call))).toList();
        List<ReactToolRegistry.TimedObservation> observations;
        try {
            observations = toolRegistry.executeBatch(allowed, invocation.context().knowledgeScope(),
                    invocation.context().remaining());
        } catch (ReactToolRegistry.BatchExecutionException exception) {
            throw new AgentExecutionException(exception.status(), exception.getMessage());
        }
        int index = 0;
        for (ToolExecutionRequest call : calls) {
            var timed = invocation.definition().role().allowsTool(toolRegistry.effectiveToolName(call))
                    ? observations.get(index++)
                    : new ReactToolRegistry.TimedObservation(
                            new ReactToolRegistry.ToolObservation(false, null, "工具不在当前Agent的授权范围内"), 0);
            ReactToolRegistry.ToolObservation observation = timed.observation();
            invocation.evidence().add(new AgentEvidence(call.name(), observation.arguments(),
                    observation.result(), observation.success()));
            invocation.context().recordStep(new ReactStepVO(0, invocation.task().taskNumber(), "TOOL",
                    observation.success() ? "SUCCESS" : "ERROR",
                    observation.summary(), call.name(),
                    observation.arguments(), observation.result(), timed.durationMs(),
                    invocation.definition().role().displayName()));
            invocation.messages().add(ToolExecutionResultMessage.builder().id(call.id()).toolName(call.name())
                    .text(observation.result()).isError(!observation.success()).build());
            invocation.context().remaining();
        }
    }

    /**
     * 记录当前角色公开动作，不保存内部思维链。
     *
     * @param invocation 单次Agent调用
     * @param type 轨迹类型
     * @param status 动作状态
     * @param summary 公开摘要
     * @param durationMs 动作耗时
     */
    private void trace(Invocation invocation, String type, String status, String summary, long durationMs) {
        invocation.context().recordStep(new ReactStepVO(0, invocation.task().taskNumber(), type, status,
                summary, null, null, null, durationMs, invocation.definition().role().displayName()));
    }

    /**
     * 计算单调时钟经过的毫秒数。
     *
     * @param started 计时起点
     * @return 经过的毫秒数
     */
    private static long elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    /** 仅在本次调用中存在的角色上下文，不能跨任务共享。 */
    private record Invocation(AgentTask task, AgentDefinition definition, AgentExecutionContext context,
                              List<ChatMessage> messages, List<AgentEvidence> evidence) {
    }
}
