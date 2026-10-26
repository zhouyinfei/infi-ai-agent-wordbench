package com.infi.agent.multi;

import com.infi.agent.approval.HumanApprovalContext;
import com.infi.agent.react.tool.KnowledgeSearchScope;
import com.infi.agent.config.MultiAgentProperties;
import com.infi.agent.react.model.TokenUsageTracker;
import com.infi.agent.vo.ReactRunVO;
import com.infi.agent.vo.ReactStepVO;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.output.TokenUsage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 单次同步协作的共享预算和轨迹，不保存角色或任何Agent的模型对话。 */
public class AgentExecutionContext {
    private final KnowledgeSearchScope knowledgeScope;
    private final String runId = com.infi.agent.common.ExecutionLogs.runId();
    private final long started = HumanApprovalContext.nanoTime();
    private final Duration timeout;
    private final int maxModelCalls;
    private final int maxToolCalls;
    private final int maxRepeatedCalls;
    private final int maxAssignments;
    private final TokenUsageTracker usage = new TokenUsageTracker();
    private final Map<String, Integer> repetitions = new HashMap<>();
    private final List<ReactStepVO> steps = new ArrayList<>();
    private int modelCalls;
    private int toolCalls;

    /**
     * 固定本次协作的预算快照，避免运行中配置变化影响已开始任务。
     *
     * @param properties 服务端协作配置
     */
    public AgentExecutionContext(MultiAgentProperties properties) {
        this(properties, new KnowledgeSearchScope(
                KnowledgeSearchScope.DEFAULT_MAX_SEARCHES));
    }

    /** 创建整次协作共享的检索缓存及预算，不随角色切换重置。 */
    public AgentExecutionContext(MultiAgentProperties properties,
                                 KnowledgeSearchScope knowledgeScope) {
        this.knowledgeScope = knowledgeScope;
        timeout = properties.getTaskTimeout();
        maxModelCalls = properties.getMaxIterations();
        maxToolCalls = properties.getMaxToolCalls();
        maxRepeatedCalls = properties.getMaxRepeatedCalls();
        maxAssignments = Math.min(properties.getMaxTasks(), maxModelCalls - 2);
    }

    /** @return 所有专业任务共享的本次检索上下文 */
    public KnowledgeSearchScope knowledgeScope() {
        return knowledgeScope;
    }

    /** @return 当前预算允许的专业任务数量 */
    public int maxAssignments() {
        return maxAssignments;
    }

    /** @return 当前截止时间内的剩余时长，超时则停止 */
    public Duration remaining() {
        Duration left = timeout.minusNanos(HumanApprovalContext.nanoTime() - started);
        if (left.isNegative() || left.toMillis() < 1) {
            throw new AgentExecutionException("TIMEOUT", "任务超过执行时限");
        }
        return left;
    }

    /** @return 扣除一次模型调用后的剩余时长，超出总预算时停止 */
    public Duration beginModelCall() {
        Duration left = remaining();
        if (modelCalls >= maxModelCalls) {
            throw new AgentExecutionException("LIMIT_REACHED", "达到整次任务模型调用次数上限");
        }
        modelCalls++;
        return left;
    }

    /**
     * 累计模型服务真实返回的用量。
     *
     * @param tokenUsage 模型用量，未提供时为null
     */
    public void reportUsage(TokenUsage tokenUsage) {
        usage.add(tokenUsage);
    }

    /**
     * 在执行同轮工具前检查整个批次预算。
     *
     * @param size 本轮工具调用数量
     */
    public void checkToolCapacity(int size) {
        if (toolCalls + size > maxToolCalls) {
            throw new AgentExecutionException("LIMIT_REACHED", "达到整次任务工具调用次数上限");
        }
    }

    /**
     * 计入单次工具尝试，跨所有Agent限制重复参数。
     *
     * @param call 模型提出的工具调用
     */
    public void beginToolCall(ToolExecutionRequest call) {
        remaining();
        checkToolCapacity(1);
        String signature = call.name() + ":" + call.arguments();
        if (!KnowledgeSearchScope.TOOL_NAME.equals(call.name())
                && repetitions.merge(signature, 1, Integer::sum) > maxRepeatedCalls) {
            throw new AgentExecutionException("LIMIT_REACHED", "重复调用相同工具参数，已停止无进展循环");
        }
        toolCalls++;
    }

    /**
     * 给公开轨迹分配全局序号，并隔离JSON参数引用。
     *
     * @param step Agent上报的公开动作，不包含内部思维链
     */
    public void recordStep(ReactStepVO step) {
        steps.add(new ReactStepVO(steps.size() + 1, step.iteration(), step.type(), step.status(),
                step.summary(), step.toolName(), step.arguments() == null ? null : step.arguments().deepCopy(),
                step.observation(), step.durationMs(), step.agentName()));
    }

    /**
     * 生成整次协作结果，包含所有Agent的累计调用和用量。
     *
     * @param status 最终状态
     * @param answer 最终回答
     * @param reason 停止原因
     * @return 执行结果快照
     */
    public ReactRunVO finish(String status, String answer, String reason) {
        return new ReactRunVO(runId, status, answer, reason, modelCalls, toolCalls,
                Duration.ofNanos(HumanApprovalContext.nanoTime() - started).toMillis(),
                HumanApprovalContext.withApprovalSteps(steps), usage.snapshot(modelCalls));
    }
}
