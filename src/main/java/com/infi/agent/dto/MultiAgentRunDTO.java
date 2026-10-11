package com.infi.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 单次协作目标，角色分工由协调模型生成。 */
public record MultiAgentRunDTO(
        @NotBlank(message = "任务目标不能为空")
        @Size(max = 2000, message = "任务目标不能超过2000字") String goal) {
}
