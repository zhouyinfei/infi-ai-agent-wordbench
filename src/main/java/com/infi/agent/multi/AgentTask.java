package com.infi.agent.multi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.langchain4j.data.message.ChatMessage;
import java.util.List;

/** 子任务协议，只交付公开会话历史和结构化结果，不共享其他Agent的内部消息。 */
public record AgentTask(int taskNumber, String overallGoal, String goal, List<AgentResult> previousResults,
                        @JsonIgnore List<ChatMessage> conversationHistory) {
    /** 创建公开历史及交付结果列表的不可变快照。 */
    public AgentTask {
        previousResults = List.copyOf(previousResults);
        conversationHistory = List.copyOf(conversationHistory);
    }

    /**
     * 兼容没有公共会话记忆的独立任务。
     *
     * @param taskNumber 子任务序号
     * @param overallGoal 原始目标
     * @param goal 专业目标
     * @param previousResults 此前的结构化交付
     */
    public AgentTask(int taskNumber, String overallGoal, String goal, List<AgentResult> previousResults) {
        this(taskNumber, overallGoal, goal, previousResults, List.of());
    }
}
