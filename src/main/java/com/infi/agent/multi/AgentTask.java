package com.infi.agent.multi;

import java.util.List;

/** 子任务协议，仅传递目标和结构化交付结果，不共享原始模型消息。 */
public record AgentTask(int taskNumber, String overallGoal, String goal, List<AgentResult> previousResults) {
    /** 创建交付结果列表的不可变快照。 */
    public AgentTask {
        previousResults = List.copyOf(previousResults);
    }
}
