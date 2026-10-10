package com.infi.agent.vo;

import java.util.List;

/** 单次执行结果；失败和预算终止同样保留轨迹及已返回的 token 用量。 */
public record ReactRunVO(String runId, String status, String answer, String stopReason,
                         int modelCalls, int toolCalls, long durationMs, List<ReactStepVO> steps,
                         TokenUsageVO tokenUsage) {
    /**
     * 兼容没有携带 token 元数据的结果构造，未报告用量保持未知。
     *
     * @param runId 执行编号
     * @param status 最终状态
     * @param answer 最终回答
     * @param stopReason 停止原因
     * @param modelCalls 模型调用次数
     * @param toolCalls 工具调用次数
     * @param durationMs 总耗时
     * @param steps 执行轨迹
     */
    public ReactRunVO(String runId, String status, String answer, String stopReason,
                      int modelCalls, int toolCalls, long durationMs, List<ReactStepVO> steps) {
        this(runId, status, answer, stopReason, modelCalls, toolCalls, durationMs, steps,
                modelCalls == 0 ? new TokenUsageVO(0L, 0L, 0L, 0, true)
                        : new TokenUsageVO(null, null, null, 0, false));
    }
}
