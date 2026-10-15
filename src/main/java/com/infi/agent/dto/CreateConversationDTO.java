package com.infi.agent.dto;

import jakarta.validation.constraints.Size;

/** 创建会话，可省略标题使用默认名称。 */
public record CreateConversationDTO(@Size(max = 80, message = "会话标题不能超过80字") String title) {
}
