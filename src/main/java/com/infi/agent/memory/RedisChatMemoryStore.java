package com.infi.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.config.ChatMemoryProperties;
import com.infi.agent.entity.ConversationDO;
import com.infi.agent.service.impl.ConversationPersistenceService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 标准ChatMemoryStore实现：MySQL权威快照与带版本Redis缓存，不依赖RedisJSON模块。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {
    private static final String PREFIX = "agent:chat-memory:";
    private final StringRedisTemplate redisTemplate;
    private final ConversationPersistenceService persistence;
    private final ChatMemoryProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 校验会话归属及版本后读取缓存，未命中、损坏或Redis不可用时恢复MySQL快照。
     *
     * @param memoryId 包含当前用户身份的ConversationMemoryId
     * @return 非空消息列表，只包含完整的用户和最终回答对
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        ConversationMemoryId id = identity(memoryId);
        ConversationDO metadata = persistence.requireOwned(id.userId(), id.conversationId());
        try {
            String cached = redisTemplate.opsForValue().get(key(id));
            if (cached != null) {
                JsonNode envelope = objectMapper.readTree(cached);
                if (envelope.path("version").canConvertToLong()
                        && envelope.path("version").asLong() == metadata.getMemoryVersion()
                        && envelope.path("messages").isTextual()) {
                    return normalized(ChatMessageDeserializer.messagesFromJson(envelope.get("messages").asText()));
                }
            }
        } catch (JsonProcessingException | RuntimeException exception) {
            log.debug("会话缓存未命中或不可用，将恢复MySQL快照");
        }
        ConversationDO snapshot = persistence.snapshot(id);
        List<ChatMessage> messages = normalized(ChatMessageDeserializer.messagesFromJson(snapshot.getMemoryJson()));
        publish(id, snapshot.getMemoryVersion(), ChatMessageSerializer.messagesToJson(messages));
        return messages;
    }

    /**
     * 持久化当前窗口，只有数据库事务提交后才发布缓存，回滚不会污染Redis。
     *
     * @param memoryId 持有有效写租约的记忆标识
     * @param messages LangChain4j提供的窗口消息
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        ConversationMemoryId id = identity(memoryId);
        String json = ChatMessageSerializer.messagesToJson(normalized(messages));
        ConversationDO snapshot = persistence.updateSnapshot(id, json);
        afterCommit(() -> publish(id, snapshot.getMemoryVersion(), json));
    }

    /**
     * 清空持久化窗口并使缓存失效，完整历史由会话服务在同一事务清除。
     *
     * @param memoryId 持有有效写租约的记忆标识
     */
    @Override
    public void deleteMessages(Object memoryId) {
        ConversationMemoryId id = identity(memoryId);
        persistence.updateSnapshot(id, "[]");
        afterCommit(() -> {
            try {
                redisTemplate.delete(key(id));
            } catch (RuntimeException exception) {
                log.debug("Redis清理暂不可用，旧缓存将通过版本检查失效");
            }
        });
    }

    /**
     * 只保留完整公开对话，按消息数与字符预算淘汰最早的完整轮次。
     *
     * @param messages 待验证消息
     * @return 有界窗口，不保存系统提示或工具协议
     */
    private List<ChatMessage> normalized(List<ChatMessage> messages) {
        if (messages.size() % 2 != 0) {
            throw new IllegalArgumentException("会话记忆必须包含完整对话轮次");
        }
        for (int index = 0; index < messages.size(); index += 2) {
            if (!(messages.get(index) instanceof UserMessage user) || !user.hasSingleText()
                    || !(messages.get(index + 1) instanceof AiMessage ai) || ai.hasToolExecutionRequests()
                    || ai.text() == null || ai.text().isBlank()) {
                throw new IllegalArgumentException("会话记忆仅保存用户文本与最终回答");
            }
        }
        List<ChatMessage> window = new ArrayList<>(messages);
        while (!window.isEmpty() && (window.size() > properties.getMaxMessages()
                || ChatMessageSerializer.messagesToJson(window).length() > properties.getMaxCharacters())) {
            window.subList(0, 2).clear();
        }
        return List.copyOf(window);
    }

    /**
     * @param memoryId 服务端记忆标识
     * @return 经类型校验的用户和会话身份
     */
    private ConversationMemoryId identity(Object memoryId) {
        if (!(memoryId instanceof ConversationMemoryId id) || id.userId() == null) {
            throw new IllegalArgumentException("须提供可信的会话记忆标识");
        }
        return id;
    }

    /**
     * @param id 服务端身份
     * @return 同时包含用户和会话的缓存键
     */
    private String key(ConversationMemoryId id) {
        return PREFIX + id.userId() + ":" + id.conversationId();
    }

    /**
     * 缓存发布失败不影响已提交的MySQL历史，版本校验防止使用旧值。
     *
     * @param id 记忆身份
     * @param version 持久化版本
     * @param json LangChain4j消息JSON
     */
    private void publish(ConversationMemoryId id, long version, String json) {
        try {
            String envelope = objectMapper.createObjectNode().put("version", version).put("messages", json).toString();
            redisTemplate.opsForValue().set(key(id), envelope, properties.getCacheTtl());
        } catch (RuntimeException exception) {
            log.debug("Redis缓存写入暂不可用，会话已保存在MySQL");
        }
    }

    /**
     * @param action 事务提交后执行的缓存动作，无事务时立即执行
     */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
