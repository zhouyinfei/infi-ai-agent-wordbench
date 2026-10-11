package com.infi.agent.vo;

import com.fasterxml.jackson.databind.JsonNode;

/** 执行轨迹，仅公开角色、动作与观察，不保存模型内部思维链。 */
public record ReactStepVO(int sequence, int iteration, String type, String status,
                          String summary, String toolName, JsonNode arguments,
                          String observation, long durationMs, String agentName) {
    /**
     * 兼容没有专业角色标识的ReAct与Plan-and-Execute轨迹。
     *
     * @param sequence 轨迹序号
     * @param iteration 轮次或任务序号
     * @param type 轨迹类型
     * @param status 动作状态
     * @param summary 动作摘要
     * @param toolName 工具名称
     * @param arguments 工具参数或结构化分工
     * @param observation 实际观察结果
     * @param durationMs 动作耗时
     */
    public ReactStepVO(int sequence, int iteration, String type, String status, String summary,
                       String toolName, JsonNode arguments, String observation, long durationMs) {
        this(sequence, iteration, type, status, summary, toolName, arguments, observation, durationMs, null);
    }
}
