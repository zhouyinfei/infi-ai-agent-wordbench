package com.infi.agent.multi;

/** 固定配置的独立Agent实例，通用执行流程与专业输出规则可分别扩展。 */
public class ConfiguredAgent implements Agent {
    private final AgentDefinition definition;
    private final DefaultAgentExecutor executor;
    private final AgentOutputHandler outputHandler;

    /**
     * 创建不可修改职责的Agent实例，不在实例中保存会话。
     *
     * @param definition 固定角色配置
     * @param executor 无状态通用执行器
     * @param outputHandler 专业交付处理策略
     */
    public ConfiguredAgent(AgentDefinition definition, DefaultAgentExecutor executor, AgentOutputHandler outputHandler) {
        this.definition = definition;
        this.executor = executor;
        this.outputHandler = outputHandler;
    }

    /** @return 当前实例的固定角色 */
    @Override
    public AgentRole role() {
        return definition.role();
    }

    /**
     * 独立执行任务并应用专业交付规则，失败返回结构化状态。
     *
     * @param task 子任务
     * @param context 父执行预算
     * @return 该Agent的交付结果
     */
    @Override
    public AgentResult execute(AgentTask task, AgentExecutionContext context) {
        AgentResult result = executor.execute(task, definition, context);
        if (!result.successful()) {
            return result;
        }
        try {
            context.remaining();
            AgentResult processed = outputHandler.process(task, result, context);
            context.remaining();
            return processed;
        } catch (AgentExecutionException exception) {
            return AgentResult.of(task, role(), exception.status(), exception.getMessage(), result.evidence());
        }
    }
}
