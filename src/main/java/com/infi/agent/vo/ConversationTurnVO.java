package com.infi.agent.vo;

import java.time.LocalDateTime;

/** 向所属用户展示的公开轮次，不暴露持久化内部JSON。 */
public record ConversationTurnVO(Long id, String mode, String goal, String answer, String status,
                                 LocalDateTime createdAt) {
}
