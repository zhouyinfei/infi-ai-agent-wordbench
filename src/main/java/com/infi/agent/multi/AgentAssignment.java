package com.infi.agent.multi;

/** 协调者的结构化委派，列表顺序表示依赖执行顺序。 */
public record AgentAssignment(AgentRole agent, String goal) {
}
