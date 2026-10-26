package com.infi.agent.approval;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.infi.agent.react.tool.ReactToolRegistry.BatchExecutionException;
import com.infi.agent.vo.ReactStepVO;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** 单次任务的人工检查点；审批等待不消耗模型/工具执行时限。 */
public final class HumanApprovalContext implements AutoCloseable {
    public static final String TOOL_NAME = "request_approval";
    public static final int MAX_APPROVALS = 3;
    public static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(5);
    public static final Duration MAX_TOTAL_WAIT = APPROVAL_TIMEOUT.multipliedBy(MAX_APPROVALS);
    public static final String INSTRUCTION = """
            对下一项操作自行判断是否需要当前用户确认。删除、覆盖重要文件、不可逆变更、
            对外提交或有费用的操作，以及用户明确要求先确认的操作，应使用request_approval。
            普通问答、计算和只读查询通常直接完成，无需审批。
            request_approval中填写公开的审批原因reason、待执行工具toolName、该工具完整JSON参数字符串toolArguments。
            该工具会暂停任务等待当前用户批准，并由服务端执行这一项精确操作，不要同时直接调用目标工具。
            request_approval必须单独调用，同一轮不得再提出其他工具调用。
            每次批准只适用于这一项操作，不表示批准其他操作。不得编造批准结果。
            """;
    private static final ThreadLocal<HumanApprovalContext> CURRENT = new ThreadLocal<>();
    private final BiConsumer<String, ToolExecutionRequest> checkpoint;
    private long waitedNanos;
    private int approvals;
    private final List<ReactStepVO> approvalSteps = new ArrayList<>();

    /**
     * 创建并绑定当前线程的人工确认上下文，不允许嵌套创建。
     *
     * @param checkpoint 由当前任务持有的确认回调
     */
    public HumanApprovalContext(BiConsumer<String, ToolExecutionRequest> checkpoint) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("不允许嵌套人工确认上下文");
        }
        this.checkpoint = checkpoint;
        CURRENT.set(this);
    }

    /**
     * 判断当前线程是否已绑定人工确认上下文。
     *
     * @return 是否处于支持暂停的工作台任务中
     */
    public static boolean active() {
        return CURRENT.get() != null;
    }

    /**
     * 获取扣除已累计审批等待时间的单调时钟值，用于计算任务执行耗时。
     *
     * @return 排除当前任务审批等待的单调时钟
     */
    public static long nanoTime() {
        HumanApprovalContext context = CURRENT.get();
        return System.nanoTime() - (context == null ? 0L : context.waitedNanos);
    }

    /**
     * 获取当前任务的人工确认规则；未启用人工确认时返回空字符串。
     *
     * @return 当前执行的人工确认规则
     */
    public static String instruction() {
        return active() ? INSTRUCTION : "";
    }

    /**
     * 检查人工确认次数上限并调用确认回调，在回调结束后累计等待时间、记录审批结果。
     *
     * @param reason 公开原因
     * @param call 将要执行的精确工具请求
     */
    public static void await(String reason, ToolExecutionRequest call) {
        HumanApprovalContext context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException("当前入口不支持人工确认");
        }
        if (++context.approvals > MAX_APPROVALS) {
            throw new BatchExecutionException(
                    "LIMIT_REACHED", "单次任务最多发起3次人工确认");
        }
        long started = System.nanoTime();
        String status = "APPROVED";
        try {
            context.checkpoint.accept(reason, call);
        } catch (BatchExecutionException exception) {
            status = exception.status();
            throw exception;
        } catch (RuntimeException exception) {
            status = "FAILED";
            throw exception;
        } finally {
            context.waitedNanos += System.nanoTime() - started;
            var parameters = JsonNodeFactory.instance.objectNode().put("reason", reason)
                    .put("toolArguments", call.arguments());
            context.approvalSteps.add(new ReactStepVO(0, 0, "APPROVAL", status,
                    "APPROVED".equals(status) ? "当前用户批准了此项操作" : "人工确认未通过，任务停止",
                    call.name(), parameters, status, Duration.ofNanos(System.nanoTime() - started).toMillis()));
        }
    }

    /**
     * 将当前上下文的审批记录追加到执行轨迹末尾，重新编号并返回不可修改的快照。
     *
     * @param steps 执行轨迹
     * @return 包含当前用户审批记录的持久化快照
     */
    public static List<ReactStepVO> withApprovalSteps(List<ReactStepVO> steps) {
        HumanApprovalContext context = CURRENT.get();
        if (context == null || context.approvalSteps.isEmpty()) {
            return List.copyOf(steps);
        }
        List<ReactStepVO> merged = new ArrayList<>(steps);
        for (ReactStepVO step : context.approvalSteps) {
            merged.add(new ReactStepVO(merged.size() + 1, step.iteration(), step.type(), step.status(),
                    step.summary(), step.toolName(), step.arguments(), step.observation(), step.durationMs()));
        }
        return List.copyOf(merged);
    }

    /** 清除当前线程绑定的人工确认上下文。 */
    @Override
    public void close() {
        CURRENT.remove();
    }
}
