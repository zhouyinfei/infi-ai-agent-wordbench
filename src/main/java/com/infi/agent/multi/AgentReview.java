package com.infi.agent.multi;

/** 审校Agent的结构化结论，不属于人工审批。 */
public record AgentReview(boolean approved, String answer, String reason) {
}
