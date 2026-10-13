package com.infi.agent.multi;

import java.util.List;

/** 子Agent的交付协议，显式携带身份、任务、状态、证据和专业输出。 */
public record AgentResult(int taskNumber, AgentRole agent, String goal, String status,
                          String result, String stopReason, List<AgentEvidence> evidence,
                          List<AgentAssignment> assignments, AgentReview review) {
    /** 将跨Agent传递的列表保存为不可变快照。 */
    public AgentResult {
        evidence = List.copyOf(evidence);
        assignments = List.copyOf(assignments);
    }

    /** @return 子任务是否成功完成 */
    public boolean successful() {
        return "SUCCEEDED".equals(status);
    }

    /**
     * 创建未包含专业结构的基础交付结果。
     *
     * @param task 子任务
     * @param role 固定角色
     * @param status 完成或停止状态
     * @param text 完成结果或停止原因
     * @param evidence 实际工具证据
     * @return 不可变交付结果
     */
    public static AgentResult of(AgentTask task, AgentRole role, String status,
                                 String text, List<AgentEvidence> evidence) {
        boolean success = "SUCCEEDED".equals(status);
        return new AgentResult(task.taskNumber(), role, task.goal(), status, success ? text : null,
                success ? null : text, evidence, List.of(), null);
    }

    /**
     * 为协调结果增加经校验的分工。
     *
     * @param tasks 协调者分配的任务
     * @return 包含分工的交付快照
     */
    public AgentResult withAssignments(List<AgentAssignment> tasks) {
        return new AgentResult(taskNumber, agent, goal, status, result, stopReason, evidence, tasks, null);
    }

    /**
     * 为审校结果增加专业结论。
     *
     * @param decision 经校验的审校结论
     * @return 审校交付结果
     */
    public AgentResult withReview(AgentReview decision) {
        return new AgentResult(taskNumber, agent, goal, status, decision.answer(), stopReason,
                evidence, assignments, decision);
    }
}
