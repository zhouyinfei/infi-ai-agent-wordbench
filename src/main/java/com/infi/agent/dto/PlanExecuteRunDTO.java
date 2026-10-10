package com.infi.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Plan-and-Execute 单次目标，工具范围由服务端管理。 */
public record PlanExecuteRunDTO(
        @NotBlank(message = "任务目标不能为空")
        @Size(max = 2000, message = "任务目标不能超过2000字") String goal) {
}
