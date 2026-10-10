package com.infi.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 单次任务目标，工具由模型从服务端注册工具中自动选择，无跨任务记忆。 */
public record ReactRunDTO(
        @NotBlank(message = "任务目标不能为空")
        @Size(max = 2000, message = "任务目标不能超过2000字") String goal) {
}
