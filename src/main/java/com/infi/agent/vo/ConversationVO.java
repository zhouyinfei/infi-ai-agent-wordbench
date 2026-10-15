package com.infi.agent.vo;

import java.time.LocalDateTime;

/** 仅向所属用户公开会话信息，不暴露锁和记忆内部数据。 */
public record ConversationVO(String id, String title, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
