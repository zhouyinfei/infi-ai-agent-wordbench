package com.infi.agent.service.impl;

import com.infi.agent.common.BusinessException;
import com.infi.agent.config.AiModelProperties;
import com.infi.agent.config.MultiAgentProperties;
import com.infi.agent.dto.MultiAgentRunDTO;
import com.infi.agent.multi.AgentAssignment;
import com.infi.agent.multi.AgentExecutionContext;
import com.infi.agent.multi.AgentRegistry;
import com.infi.agent.multi.AgentResult;
import com.infi.agent.multi.AgentRole;
import com.infi.agent.multi.AgentTask;
import com.infi.agent.react.model.ReactModelGateway;
import com.infi.agent.react.tool.ReactToolRegistry;
import com.infi.agent.service.MultiAgentService;
import com.infi.agent.vo.ReactConfigVO;
import com.infi.agent.vo.ReactRunVO;
import com.infi.agent.vo.ReactStepVO;
import dev.langchain4j.data.message.ChatMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 协作编排服务：委派独立Agent并协调结构化交付，不持有提示词、模型消息或工具循环。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiAgentServiceImpl implements MultiAgentService {
    private static final int MAX_TASK_GOAL_LENGTH = 500;
    private final ReactModelGateway modelGateway;
    private final ReactToolRegistry toolRegistry;
    private final MultiAgentProperties properties;
    private final AgentRegistry agents;
    private final AiModelProperties modelProperties;

    /** @return 不含凭证的模型状态、整体预算及工具信息 */
    @Override
    public ReactConfigVO configuration() {
        return new ReactConfigVO(modelGateway.isConfigured(), modelProperties.getModelName(),
                properties.getMaxIterations(), properties.getMaxToolCalls(),
                properties.getTaskTimeout().toSeconds(), toolRegistry.descriptions());
    }

    /**
     * 委派协调、专业和审校任务，只消费子Agent的结构化交付。
     *
     * @param userId 已认证用户 ID，用于统计日志
     * @param request 已校验的目标
     * @return 整次执行的结果和累计统计
     */
    @Override
    public ReactRunVO execute(Long userId, MultiAgentRunDTO request) {
        return execute(userId, request, List.of());
    }

    /**
     * 接收有限的公共会话历史，系统规则和角色权限仍由服务端确定。
     *
     * @param userId 已认证用户
     * @param request 当前目标
     * @param history 近期公开对话，不含其他Agent的内部消息
     * @return 执行结果和真实统计
     */
    @Override
    public ReactRunVO execute(Long userId, MultiAgentRunDTO request, List<ChatMessage> history) {
        validateConfiguration();
        AgentExecutionContext context = new AgentExecutionContext(properties);
        if (context.maxAssignments() < 1) {
            return finish(userId, context, "LIMIT_REACHED", null, "模型预算不足以完成协作");
        }
        AgentTask planningTask = new AgentTask(0, request.goal(),
                "最多" + context.maxAssignments() + "个任务，每个目标最多" + MAX_TASK_GOAL_LENGTH + "字。请分配角色任务。", List.of(), history);
        AgentResult coordination = delegate(AgentRole.COORDINATOR, planningTask, context);
        if (!coordination.successful()) {
            return stopped(userId, context, coordination);
        }
        if (coordination.assignments().isEmpty() || coordination.assignments().size() > context.maxAssignments()
                || coordination.assignments().stream().anyMatch(assignment -> assignment == null
                || assignment.agent() == null || !assignment.agent().isWorker() || assignment.goal() == null
                || assignment.goal().isBlank() || assignment.goal().length() > MAX_TASK_GOAL_LENGTH)) {
            return finish(userId, context, "FAILED", null, "协调Agent交付的分工不符合角色或预算约束");
        }
        List<AgentResult> results = new ArrayList<>();
        for (AgentAssignment assignment : coordination.assignments()) {
            AgentTask task = new AgentTask(results.size() + 1, request.goal(), assignment.goal(), results, history);
            AgentResult result = delegate(assignment.agent(), task, context);
            if (!result.successful()) {
                return stopped(userId, context, result);
            }
            results.add(result);
            context.recordStep(new ReactStepVO(0, task.taskNumber(), "AGENT", "SUCCESS",
                    "完成协作任务：" + task.goal(), null, null, result.result(), 0L, result.agent().displayName()));
        }
        AgentTask reviewTask = new AgentTask(results.size() + 1, request.goal(), "核对交付结果和真实工具证据", results, history);
        AgentResult review = delegate(AgentRole.REVIEWER, reviewTask, context);
        if (!review.successful()) {
            return stopped(userId, context, review);
        }
        if (review.review() == null || !review.review().approved() || review.result() == null || review.result().isBlank()) {
            return finish(userId, context, "FAILED", null, "审校Agent没有交付有效的通过结论");
        }
        return finish(userId, context, "SUCCEEDED", review.result(), null);
    }

    /**
     * 委派任务并校验交付身份，专业实现不能冒用其他角色或任务的结果。
     *
     * @param role 目标Agent角色
     * @param task 此次委派的任务
     * @param context 共享预算和轨迹
     * @return 身份与任务匹配的交付或明确失败状态
     */
    private AgentResult delegate(AgentRole role, AgentTask task, AgentExecutionContext context) {
        AgentResult result = agents.agent(role).execute(task, context);
        if (result == null || result.agent() != role || result.taskNumber() != task.taskNumber()
                || !task.goal().equals(result.goal())) {
            return AgentResult.of(task, role, "FAILED", "Agent交付的角色或任务身份不匹配", List.of());
        }
        return result;
    }

    /** 检查模型启用状态及协作截止时间配置，拒绝无效配置。 */
    private void validateConfiguration() {
        if (!modelGateway.isConfigured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_NOT_CONFIGURED",
                    "请先配置并启用支持工具调用的模型");
        }
        Duration timeout = properties.getTaskTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "INVALID_MULTI_AGENT_CONFIG", "任务超时配置不合法");
        }
    }

    /**
     * 子Agent未完成时停止后续委派，不将部分结果当作最终成功。
     *
     * @param userId 当前用户
     * @param context 父执行统计
     * @param result 子Agent停止交付
     * @return 失败或预算终止的整次执行结果
     */
    private ReactRunVO stopped(Long userId, AgentExecutionContext context, AgentResult result) {
        return finish(userId, context, result.status(), null, result.stopReason());
    }

    /**
     * 生成整体结果并记录统计日志。
     *
     * @param userId 当前用户
     * @param context 父执行状态
     * @param status 最终状态
     * @param answer 最终回答
     * @param reason 停止原因
     * @return 公开执行结果
     */
    private ReactRunVO finish(Long userId, AgentExecutionContext context, String status, String answer, String reason) {
        ReactRunVO result = context.finish(status, answer, reason);
        log.info("Multi-Agent completed, userId={}, runId={}, status={}, modelCalls={}, toolCalls={}",
                userId, result.runId(), result.status(), result.modelCalls(), result.toolCalls());
        return result;
    }
}
