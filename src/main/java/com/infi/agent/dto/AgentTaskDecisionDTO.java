package com.infi.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 绑定具体审批编号，禁止过期页面误批准后续动作。 */
public record AgentTaskDecisionDTO(@NotBlank @Size(max = 36) String approvalId,
                                    @NotNull Boolean approved) {
}
