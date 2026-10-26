package com.infi.agent.service.impl;

import com.infi.agent.approval.HumanApprovalContext;
import com.infi.agent.common.BusinessException;
import com.infi.agent.dto.AgentTaskDecisionDTO;
import com.infi.agent.dto.AgentTaskSubmitDTO;
import com.infi.agent.dto.MultiAgentRunDTO;
import com.infi.agent.dto.PlanExecuteRunDTO;
import com.infi.agent.dto.ReactRunDTO;
import com.infi.agent.react.model.ModelSelection;
import com.infi.agent.react.tool.ReactToolRegistry;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.service.AgentTaskService;
import com.infi.agent.service.ConversationService;
import com.infi.agent.service.MultiAgentService;
import com.infi.agent.service.PlanExecuteService;
import com.infi.agent.service.ReactService;
import com.infi.agent.vo.AgentTaskVO;
import com.infi.agent.vo.ReactRunVO;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** 有界的后台执行容器，保留当前调用栈，批准后继续原任务而非重新执行前序操作。 */
@Slf4j
@Service
public class AgentTaskServiceImpl implements AgentTaskService {
    private static final int MAX_RETAINED_TASKS = 128;
    private static final Duration RETENTION = Duration.ofMinutes(30);
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final ThreadPoolTaskExecutor executor;
    private final ConversationService conversations;
    private final ReactService react;
    private final PlanExecuteService plan;
    private final MultiAgentService multi;

    public AgentTaskServiceImpl(@Qualifier("humanTaskExecutor") ThreadPoolTaskExecutor executor,
                                ConversationService conversations, ReactService react,
                                PlanExecuteService plan, MultiAgentService multi) {
        this.executor = executor;
        this.conversations = conversations;
        this.react = react;
        this.plan = plan;
        this.multi = multi;
    }

    /**
     * 提交当前用户的智能体任务，返回可供轮询的执行快照。
     *
     * @param user 当前用户
     * @param request 正常工作台任务
     * @return 立即返回可轮询任务
     */
    @Override
    public synchronized AgentTaskVO submit(UserPrincipal user, AgentTaskSubmitDTO request) {
        tasks.values().removeIf(task -> task.finishedAt > 0
                && System.nanoTime() - task.finishedAt > RETENTION.toNanos());
        if (tasks.size() >= MAX_RETAINED_TASKS) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TASK_CAPACITY", "任务容量已满，请稍后重试");
        }
        if (tasks.values().stream().anyMatch(task -> task.userId.equals(user.id()) && task.result == null)) {
            throw new BusinessException(HttpStatus.CONFLICT, "TASK_BUSY", "已有执行中或待确认的任务，请先处理");
        }
        TaskState task = new TaskState(user.id(), request);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        tasks.put(task.id, task);
        try {
            executor.execute(() -> {
                var security = SecurityContextHolder.createEmptyContext();
                security.setAuthentication(authentication);
                SecurityContextHolder.setContext(security);
                try (HumanApprovalContext context = new HumanApprovalContext(
                        (reason, call) -> awaitDecision(task, reason, call));
                     ModelSelection selection = ModelSelection.select(request.modelId())) {
                    task.complete(dispatch(user.id(), request));
                } catch (RuntimeException exception) {
                    log.error("Background task failed, taskId={}", task.id, exception);
                    task.complete(new ReactRunVO(task.id, "FAILED", null, "任务执行异常，请检查服务状态",
                            0, 0, 0L, List.of()));
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
        } catch (RuntimeException exception) {
            tasks.remove(task.id);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "TASK_CAPACITY", "执行队列已满，请稍后重试");
        }
        return task.snapshot();
    }

    /**
     * 根据任务编号查询当前用户本人的任务执行快照。
     *
     * @param user 当前用户
     * @param id 任务编号
     * @return 用户本人的任务快照
     */
    @Override
    public AgentTaskVO get(UserPrincipal user, String id) {
        return owned(user.id(), id).snapshot();
    }

    /**
     * 查询当前用户所有尚未结束的任务。
     *
     * @param user 当前用户
     * @return 当前用户尚未结束的任务，用于刷新后恢复确认卡片
     */
    @Override
    public List<AgentTaskVO> active(UserPrincipal user) {
        return tasks.values().stream().filter(task -> task.userId.equals(user.id()) && task.result == null)
                .map(TaskState::snapshot).toList();
    }

    /**
     * 提交当前用户对任务中具体操作的人工决定，返回最新执行快照。
     *
     * @param user 当前用户
     * @param id 任务编号
     * @param decision 与具体操作绑定的决定
     * @return 最新状态
     */
    @Override
    public AgentTaskVO decide(UserPrincipal user, String id, AgentTaskDecisionDTO decision) {
        TaskState task = owned(user.id(), id);
        synchronized (task) {
            if (task.approval == null || !task.approval.id().equals(decision.approvalId())
                    || task.decision == null || !task.decision.complete(decision.approved())) {
                throw new BusinessException(HttpStatus.CONFLICT, "APPROVAL_STATE_CONFLICT", "该确认已处理或过期，请刷新");
            }
            task.status = "RUNNING";
            task.approval = null;
            return task.snapshot();
        }
    }

    /**
     * @param task 原任务
     * @param reason 模型公开理由
     * @param call 精确工具请求
     */
    private void awaitDecision(TaskState task, String reason, ToolExecutionRequest call) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        synchronized (task) {
            task.decision = future;
            task.approval = new AgentTaskVO.PendingApprovalVO(UUID.randomUUID().toString(), reason,
                    call.name(), call.arguments());
            task.status = "WAITING_APPROVAL";
        }
        try {
            if (!future.get(HumanApprovalContext.APPROVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ReactToolRegistry.BatchExecutionException("REJECTED", "当前用户拒绝了操作，任务已停止");
            }
        } catch (TimeoutException exception) {
            throw new ReactToolRegistry.BatchExecutionException("TIMEOUT", "等待用户确认超过5分钟，任务已停止");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ReactToolRegistry.BatchExecutionException("FAILED", "任务等待已中断");
        } catch (ExecutionException exception) {
            throw new ReactToolRegistry.BatchExecutionException("FAILED", "无法取得用户确认结果");
        } finally {
            synchronized (task) {
                task.approval = null;
                task.decision = null;
                task.status = "RUNNING";
            }
        }
    }

    /**
     * @param userId 用户
     * @param request 任务快照
     * @return 原编排模式的结果
     */
    private ReactRunVO dispatch(Long userId, AgentTaskSubmitDTO request) {
        if (request.conversationId() != null) {
            return conversations.execute(userId, request.conversationId(), request.mode(), request.goal(),
                    request.useKnowledge(), request.useNetwork());
        }
        return switch (request.mode()) {
            case REACT -> react.execute(userId, new ReactRunDTO(request.goal(), null,
                    request.useKnowledge(), request.useNetwork(), request.modelId()));
            case PLAN_EXECUTE -> plan.execute(userId, new PlanExecuteRunDTO(request.goal(), null,
                    request.useKnowledge(), request.useNetwork(), request.modelId()));
            case MULTI_AGENT -> multi.execute(userId, new MultiAgentRunDTO(request.goal(), null,
                    request.useKnowledge(), request.useNetwork(), request.modelId()));
        };
    }

    /**
     * @param userId 当前用户
     * @param id 任务编号
     * @return 所属记录
     */
    private TaskState owned(Long userId, String id) {
        TaskState task = tasks.get(id);
        if (task == null || !task.userId.equals(userId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在或已过期");
        }
        return task;
    }

    /** 仅在任务锁内修改审批状态，最终结果通过volatile安全发布。 */
    private static final class TaskState {
        private final String id = UUID.randomUUID().toString();
        private final Long userId;
        private final AgentTaskSubmitDTO request;
        private String status = "RUNNING";
        private AgentTaskVO.PendingApprovalVO approval;
        private CompletableFuture<Boolean> decision;
        private volatile ReactRunVO result;
        private volatile long finishedAt;

        private TaskState(Long userId, AgentTaskSubmitDTO request) {
            this.userId = userId;
            this.request = request;
        }

        private synchronized AgentTaskVO snapshot() {
            return new AgentTaskVO(id, request.conversationId(), request.mode(), request.goal(),
                    status, approval, result);
        }

        private synchronized void complete(ReactRunVO outcome) {
            status = outcome.status();
            approval = null;
            finishedAt = System.nanoTime();
            result = outcome;
        }
    }
}
