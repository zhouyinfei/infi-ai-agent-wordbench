package com.infi.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 单次目标及可选会话编号，用户身份始终来自已验证令牌。 */
public record MultiAgentRunDTO(
        @NotBlank(message = "任务目标不能为空")
        @Size(max = 2000, message = "任务目标不能超过2000字") String goal,
        @Pattern(regexp = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}", message = "会话编号不合法")
        String conversationId, boolean useKnowledge, boolean useNetwork, @Size(max = 80) String modelId) {
    public MultiAgentRunDTO(String goal, String conversationId, boolean useKnowledge, boolean useNetwork) {
        this(goal, conversationId, useKnowledge, useNetwork, null);
    }
    /** 未显式开启联网时默认禁止联网工具。 */
    public MultiAgentRunDTO(String goal, String conversationId, boolean useKnowledge) {
        this(goal, conversationId, useKnowledge, false);
    }

    /** 兼容旧请求，默认不允许知识库检索。 */
    public MultiAgentRunDTO(String goal, String conversationId) {
        this(goal, conversationId, false);
    }

    /**
     * 兼容不携带会话记忆的独立执行请求。
     *
     * @param goal 本次目标
     */
    public MultiAgentRunDTO(String goal) {
        this(goal, null, false);
    }
}
