package com.infi.agent.memory;

/** 可信服务端记忆标识，用户身份及租约不由模型构造。 */
public record ConversationMemoryId(Long userId, String conversationId, String leaseToken) {
}
