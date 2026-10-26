package com.infi.agent.vo;

import com.infi.agent.memory.ConversationMode;

/** 当前用户的运行快照；审批只包含模型提交的公开理由及具体操作。 */
public record AgentTaskVO(String id, String conversationId, ConversationMode mode, String goal, String status,
                           PendingApprovalVO approval, ReactRunVO result) {
    /** 待批准的精确工具调用，参数以JSON字符串原样展示。 */
    public record PendingApprovalVO(String id, String reason, String toolName, String arguments) {
    }
}
