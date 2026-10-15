package com.infi.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 单次目标及可选会话编号，用户身份始终来自已验证令牌。 */
public record ReactRunDTO(
        @NotBlank(message = "任务目标不能为空")
        @Size(max = 2000, message = "任务目标不能超过2000字") String goal,
        @Pattern(regexp = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}", message = "会话编号不合法")
        String conversationId) {
    /**
     * 兼容不携带会话记忆的独立执行请求。
     *
     * @param goal 本次目标
     */
    public ReactRunDTO(String goal) {
        this(goal, null);
    }
}
