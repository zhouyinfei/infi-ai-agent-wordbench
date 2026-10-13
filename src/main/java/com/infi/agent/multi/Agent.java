package com.infi.agent.multi;

/** 可独立接收任务和交付结构化结果的执行主体。 */
public interface Agent {
    /** @return 此实例的固定角色 */
    AgentRole role();

    /**
     * 在独立消息上下文中处理任务，预算由父执行共享。
     *
     * @param task 不可修改的目标和此前交付结果
     * @param context 父执行的截止时间、预算及审计轨迹
     * @return 完成状态、结果和真实工具证据
     */
    AgentResult execute(AgentTask task, AgentExecutionContext context);
}
