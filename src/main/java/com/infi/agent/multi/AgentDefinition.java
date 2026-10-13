package com.infi.agent.multi;

/** 一个Agent实例的固定系统职责、轨迹类型和工具策略。 */
public record AgentDefinition(AgentRole role, String systemPrompt, String traceType, boolean withTools) {
}
