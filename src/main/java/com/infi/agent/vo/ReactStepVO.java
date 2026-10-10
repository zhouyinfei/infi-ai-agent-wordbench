package com.infi.agent.vo;

import com.fasterxml.jackson.databind.JsonNode;

/** 执行轨迹，仅公开动作与观察，不保存模型内部思维链。 */
public record ReactStepVO(int sequence, int iteration, String type, String status,
                          String summary, String toolName, JsonNode arguments,
                          String observation, long durationMs) {
}
