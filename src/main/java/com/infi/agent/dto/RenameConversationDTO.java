package com.infi.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 修改会话标题，禁止空白及超长名称。 */
public record RenameConversationDTO(
        @NotBlank(message = "会话标题不能为空")
        @Size(max = 80, message = "会话标题不能超过80字") String title) {
}
