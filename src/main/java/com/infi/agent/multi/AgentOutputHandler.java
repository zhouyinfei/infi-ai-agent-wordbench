package com.infi.agent.multi;

/** 专业行为扩展点：校验和转换Agent交付，不负责模型或工具循环。 */
@FunctionalInterface
public interface AgentOutputHandler {
    /**
     * 处理子Agent交付，预算停止时直接保留失败状态。
     *
     * @param task 原始子任务
     * @param result 通用执行器交付
     * @param context 父执行的预算和轨迹
     * @return 经专业规则校验的结果
     */
    AgentResult process(AgentTask task, AgentResult result, AgentExecutionContext context);
}
