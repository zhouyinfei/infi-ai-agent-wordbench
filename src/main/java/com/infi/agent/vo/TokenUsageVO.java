package com.infi.agent.vo;

/**
 * 模型服务返回的累计 token 用量；null 表示对应计数未提供，部分统计不是完整消耗。
 *
 * @param inputTokens 已报告的输入 token 数
 * @param outputTokens 已报告的输出 token 数
 * @param totalTokens 已报告的总 token 数
 * @param reportedModelCalls 至少返回一个有效用量字段的模型调用数
 * @param complete 是否每次调用都具备输入、输出及总数统计
 */
public record TokenUsageVO(Long inputTokens, Long outputTokens, Long totalTokens,
                           int reportedModelCalls, boolean complete) {
}
