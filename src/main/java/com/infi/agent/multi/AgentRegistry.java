package com.infi.agent.multi;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 独立Agent实例注册表，协调服务只按角色取实例，不切换执行器的当前角色。 */
public class AgentRegistry {
    private final Map<AgentRole, Agent> agents;

    /**
     * 创建当前固定角色的独立实例，共用无状态执行器而不共用会话。
     *
     * @param executor 通用执行基础设施
     * @param objectMapper 专业结构化输出解析器
     */
    public AgentRegistry(DefaultAgentExecutor executor, ObjectMapper objectMapper) {
        this(defaultAgents(executor, objectMapper));
    }

    /**
     * 注册独立实现，允许按需要用专业Agent类替换通用实例。
     *
     * @param instances 必须包含全部固定角色且不重复
     */
    public AgentRegistry(List<Agent> instances) {
        Map<AgentRole, Agent> registered = new EnumMap<>(AgentRole.class);
        for (Agent agent : instances) {
            if (registered.putIfAbsent(agent.role(), agent) != null) {
                throw new IllegalStateException("Agent角色重复");
            }
        }
        if (registered.size() != AgentRole.values().length) {
            throw new IllegalStateException("Agent角色注册不完整");
        }
        agents = Map.copyOf(registered);
    }

    /**
     * 获取某个固定角色的独立执行实例。
     *
     * @param role 服务端角色
     * @return 不保存会话状态的Agent实例
     */
    public Agent agent(AgentRole role) {
        Agent agent = agents.get(role);
        if (agent == null) {
            throw new IllegalArgumentException("Agent未注册");
        }
        return agent;
    }

    /**
     * 组装固定团队，专业输出行为和模型循环分别由策略与执行器实现。
     *
     * @param executor 通用执行器
     * @param objectMapper JSON解析器
     * @return 各角色独立实例
     */
    private static List<Agent> defaultAgents(DefaultAgentExecutor executor, ObjectMapper objectMapper) {
        List<Agent> instances = new ArrayList<>();
        instances.add(new ConfiguredAgent(
                new AgentDefinition(AgentRole.COORDINATOR, AgentPrompts.PLANNER_PROMPT, "COORDINATE", false),
                executor, new CoordinatorOutputHandler(objectMapper)));
        for (AgentRole role : AgentRole.values()) {
            if (role.isWorker()) {
                instances.add(new ConfiguredAgent(new AgentDefinition(role,
                        role.description() + "\n" + AgentPrompts.EXECUTOR_PROMPT, "MODEL", true),
                        executor, (task, result, context) -> result));
            }
        }
        instances.add(new ConfiguredAgent(
                new AgentDefinition(AgentRole.REVIEWER, AgentPrompts.SUMMARY_PROMPT, "REVIEW", false),
                executor, new ReviewerOutputHandler(objectMapper)));
        return List.copyOf(instances);
    }
}
