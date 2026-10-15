package com.infi.agent;

import com.infi.agent.dto.RegisterDTO;
import com.infi.agent.memory.ConversationMemoryId;
import com.infi.agent.memory.ConversationMode;
import com.infi.agent.memory.RedisChatMemoryStore;
import com.infi.agent.react.model.ReactModelGateway;
import com.infi.agent.service.ConversationService;
import com.infi.agent.service.UserService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 显式执行：增量初始化本机业务库会话表，真实MySQL/Redis联调；不调用外部模型。 */
@SpringBootTest(properties = {"spring.sql.init.mode=never", "app.bootstrap.admin-username=", "app.bootstrap.admin-password="})
@ActiveProfiles("local")
class ConversationInfrastructureIT {
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ConversationService service;
    @Autowired private UserService users;
    @Autowired private RedisChatMemoryStore store;
    @Autowired private StringRedisTemplate redis;
    @MockitoBean private ReactModelGateway gateway;

    @Test
    void migrateConversationTablesAndVerifyRealMysqlRedisMemory() {
        new ResourceDatabasePopulator(new FileSystemResource("sql/002_conversation.sql")).execute(dataSource);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Long userId = users.register(new RegisterDTO("memit_" + suffix, "Password123!", "memory-it", null)).id();
        String id = null;
        try {
            id = service.create(userId, "isolated-memory-it").id();
            String key = "agent:chat-memory:" + userId + ":" + id;
            when(gateway.isConfigured()).thenReturn(true);
            when(gateway.chat(any(ChatRequest.class), any(Duration.class)))
                    .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("记住了联调名字")).build());
            service.execute(userId, id, ConversationMode.REACT, "我叫联调名字");
            assertThat(redis.hasKey(key)).isTrue();
            assertThat(redis.getExpire(key)).isPositive();
            redis.delete(key);
            assertThat(store.getMessages(new ConversationMemoryId(userId, id, null))).hasSize(2);
            assertThat(redis.hasKey(key)).isTrue();
            List<ChatRequest> requests = new ArrayList<>();
            when(gateway.chat(any(ChatRequest.class), any(Duration.class))).thenAnswer(invocation -> {
                requests.add(invocation.getArgument(0));
                return ChatResponse.builder().aiMessage(AiMessage.from("你叫联调名字")).build();
            });
            service.execute(userId, id, ConversationMode.REACT, "我叫什么");
            assertThat(requests.get(0).messages().toString()).contains("我叫联调名字");
            assertThat(service.history(userId, id, null).records()).hasSize(2);
            service.clear(userId, id);
            assertThat(store.getMessages(new ConversationMemoryId(userId, id, null))).isEmpty();
            assertThat(service.history(userId, id, null).records()).isEmpty();
            service.delete(userId, id);
            assertThat(redis.hasKey(key)).isFalse();
            id = null;
        } finally {
            if (id != null) {
                redis.delete("agent:chat-memory:" + userId + ":" + id);
                jdbc.update("DELETE FROM ai_conversation_turn WHERE conversation_id = ?", id);
                jdbc.update("DELETE FROM ai_conversation WHERE id = ? AND user_id = ?", id, userId);
            }
            jdbc.update("DELETE FROM sys_user WHERE id = ? AND username = ?", userId, "memit_" + suffix);
        }
    }
}
