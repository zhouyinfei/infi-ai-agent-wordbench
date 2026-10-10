package com.infi.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 单次目标和允许使用的只读工具，无跨任务记忆。 */
public record ReactRunDTO(
        @NotBlank(message = "任务目标不能为空")
        @Size(max = 2000, message = "任务目标不能超过2000字") String goal,
        @Size(min = 1, max = 2, message = "请选择1至2个工具")
        List<@NotBlank(message = "工具名称不能为空") String> allowedTools) {
}
