package com.infi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.common.BusinessException;
import com.infi.agent.config.ChatMemoryProperties;
import com.infi.agent.dto.RegisterDTO;
import com.infi.agent.memory.ConversationMemoryId;
import com.infi.agent.memory.ConversationMode;
import com.infi.agent.memory.RedisChatMemoryStore;
import com.infi.agent.react.model.ReactModelGateway;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.service.ConversationService;
import com.infi.agent.service.SessionService;
import com.infi.agent.service.UserService;
import com.infi.agent.service.impl.ConversationPersistenceService;
import com.infi.agent.vo.ReactRunVO;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实MyBatis/H2事务与可控Redis缓存验证持久化窗口、用户隔离及故障恢复。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:conversation;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConversationMemoryIntegrationTest {
    @Autowired private ConversationService service;
    @Autowired private ConversationPersistenceService persistence;
    @Autowired private RedisChatMemoryStore store;
    @Autowired private UserService userService;
    @Autowired private ChatMemoryProperties memoryProperties;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @MockitoBean private StringRedisTemplate redis;
    @MockitoBean private ReactModelGateway gateway;
    @MockitoBean private SessionService sessions;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean redisDown = new AtomicBoolean();
    private ValueOperations<String, String> values;
    private Long user;
    private Long other;
    private String id;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(invocation -> {
            if (redisDown.get()) { throw new RedisConnectionFailureException("test unavailable"); }
            return cache.get(invocation.getArgument(0));
        });
        doAnswer(invocation -> {
            if (redisDown.get()) { throw new RedisConnectionFailureException("test unavailable"); }
            cache.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(invocation -> cache.remove(invocation.getArgument(0)) != null);
        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.chat(any(ChatRequest.class), any(Duration.class))).thenReturn(response("已记住小周"));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        user = userService.register(new RegisterDTO("ma_" + suffix, "Password123!", "memory", null)).id();
        other = userService.register(new RegisterDTO("mb_" + suffix, "Password123!", "other", null)).id();
        id = service.create(user, "记忆测试").id();
        memoryProperties.setMaxMessages(20);
        memoryProperties.setMaxCharacters(32000);
    }

    @AfterEach
    void restoreProperties() {
        memoryProperties.setMaxMessages(20);
        memoryProperties.setMaxCharacters(32000);
    }

    @Test
    void secondReactTurnReceivesPersistedHistoryAndStandardStore() {
        service.execute(user, id, ConversationMode.REACT, "我叫小周");
        List<ChatRequest> requests = new ArrayList<>();
        when(gateway.chat(any(ChatRequest.class), any(Duration.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return response("你叫小周");
        });
        service.execute(user, id, ConversationMode.REACT, "我叫什么");
        assertThat(requests.get(0).messages().toString()).contains("我叫小周", "已记住小周");
        assertThat(store).isInstanceOf(ChatMemoryStore.class);
        assertThat(store.getMessages(readId())).hasSize(4);
        assertThat(service.history(user, id, null).records()).hasSize(2);
        assertThat(persistence.snapshot(readId()).getMemoryJson()).contains("你叫小周");
    }

    @Test
    void planModeUsesPublicHistoryInEveryPhase() {
        service.execute(user, id, ConversationMode.REACT, "我叫小周");
        List<ChatRequest> requests = new ArrayList<>();
        List<String> answers = List.of("{\"steps\":[\"回答姓名\"]}", "你叫小周", "你叫小周");
        when(gateway.chat(any(ChatRequest.class), any(Duration.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return response(answers.get(requests.size() - 1));
        });
        assertThat(service.execute(user, id, ConversationMode.PLAN_EXECUTE, "我叫什么").status()).isEqualTo("SUCCEEDED");
        assertThat(requests).hasSize(3);
        requests.forEach(request -> assertThat(request.messages().toString()).contains("我叫小周"));
    }

    @Test
    void multiAgentReceivesOnlyPublicConversationHistory() {
        service.execute(user, id, ConversationMode.REACT, "我叫小周");
        List<ChatRequest> requests = new ArrayList<>();
        List<String> answers = List.of("{\"tasks\":[{\"agent\":\"ANALYST\",\"goal\":\"回答姓名\"}]}",
                "你叫小周", "{\"approved\":true,\"answer\":\"你叫小周\",\"reason\":\"公开历史一致\"}");
        when(gateway.chat(any(ChatRequest.class), any(Duration.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return response(answers.get(requests.size() - 1));
        });
        var result = service.execute(user, id, ConversationMode.MULTI_AGENT, "我叫什么");
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.tokenUsage().totalTokens()).isEqualTo(21L);
        requests.forEach(request -> assertThat(request.messages().toString()).contains("我叫小周"));
        assertThat(store.getMessages(readId())).hasSize(4);
    }

    @Test
    void redisLossAndOutageRestoreMysqlWithoutLosingNewTurns() {
        service.execute(user, id, ConversationMode.REACT, "我叫小周");
        cache.clear();
        assertThat(store.getMessages(readId())).hasSize(2);
        redisDown.set(true);
        assertThat(store.getMessages(readId())).hasSize(2);
        service.execute(user, id, ConversationMode.REACT, "继续");
        redisDown.set(false);
        assertThat(store.getMessages(readId())).hasSize(4);
    }

    @Test
    void staleAndCorruptCacheCannotReplaceCurrentSnapshot() {
        service.execute(user, id, ConversationMode.REACT, "我叫小周");
        cache.put(key(), "{\"version\":0,\"messages\":\"[]\"}");
        assertThat(store.getMessages(readId())).hasSize(2);
        cache.put(key(), "invalid-cache");
        assertThat(store.getMessages(readId())).hasSize(2);
    }

    @Test
    void usersAndSeparateConversationsAreIsolated() {
        service.execute(user, id, ConversationMode.REACT, "我叫小周");
        String fresh = service.create(user, "另一会话").id();
        assertThat(store.getMessages(new ConversationMemoryId(user, fresh, null))).isEmpty();
        assertNotFound(() -> service.history(other, id, null));
        assertNotFound(() -> store.getMessages(new ConversationMemoryId(other, id, null)));
        assertNotFound(() -> service.execute(other, id, ConversationMode.REACT, "读取别人的历史"));
        assertNotFound(() -> service.clear(other, id));
        assertNotFound(() -> service.delete(other, id));
        assertThat(service.list(other)).isEmpty();
    }

    @Test
    void sameConversationConcurrentExecutionAndClearAreRejected() {
        var lease = persistence.claim(user, id, Duration.ofMinutes(3));
        try {
            assertBusy(() -> service.execute(user, id, ConversationMode.REACT, "并发请求"));
            assertBusy(() -> service.clear(user, id));
            persistence.release(new ConversationMemoryId(user, id, UUID.randomUUID().toString()));
            assertBusy(() -> service.delete(user, id));
            verify(gateway, never()).chat(any(ChatRequest.class), any(Duration.class));
        } finally { persistence.release(lease); }
    }

    @Test
    void expiredWriterCannotOverwriteNewLease() {
        var old = persistence.claim(user, id, Duration.ofMinutes(3));
        jdbc.update("UPDATE ai_conversation SET lock_deadline = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1), id);
        var fresh = persistence.claim(user, id, Duration.ofMinutes(3));
        try {
            assertBusy(() -> store.updateMessages(old, List.of(UserMessage.from("old"), AiMessage.from("old"))));
            persistence.release(old);
            assertThat(persistence.requireOwned(user, id).getLockToken()).isEqualTo(fresh.leaseToken());
            assertThat(store.getMessages(readId())).isEmpty();
        } finally { persistence.release(fresh); }
    }

    @Test
    void rollbackDoesNotPublishMemoryOrHistoryToRedis() {
        var lease = persistence.claim(user, id, Duration.ofMinutes(3));
        try {
            var memory = MessageWindowChatMemory.builder().id(lease).maxMessages(20).chatMemoryStore(store).build();
            assertThat(memory.messages()).isEmpty();
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                persistence.complete(lease, ConversationMode.REACT, "rolled-back",
                        new ReactRunVO("run", "SUCCEEDED", "must-not-leak", null, 1, 0, 1, List.of()), memory);
                transaction.setRollbackOnly();
            });
            assertThat(store.getMessages(readId())).isEmpty();
            assertThat(service.history(user, id, null).records()).isEmpty();
            assertThat(cache.get(key())).doesNotContain("must-not-leak");
        } finally { persistence.release(lease); }
    }

    @Test
    void finiteWindowRetainsPairsWhileMysqlRetainsCompleteHistory() {
        memoryProperties.setMaxMessages(4);
        for (int index = 0; index < 3; index++) {
            service.execute(user, id, ConversationMode.REACT, "goal-" + index);
        }
        List<ChatMessage> messages = store.getMessages(readId());
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.toString()).doesNotContain("goal-0").contains("goal-1", "goal-2");
        assertThat(service.history(user, id, null).records()).hasSize(3);
    }

    @Test
    void failedRunIsArchivedButNotAddedToModelMemory() {
        service.execute(user, id, ConversationMode.REACT, "successful");
        when(gateway.chat(any(ChatRequest.class), any(Duration.class))).thenReturn(response(" "));
        assertThat(service.execute(user, id, ConversationMode.REACT, "failed").status()).isEqualTo("FAILED");
        assertThat(store.getMessages(readId())).hasSize(2);
        assertThat(service.history(user, id, null).records()).extracting(turn -> turn.status())
                .containsExactly("SUCCEEDED", "FAILED");
    }

    @Test
    void clearingAndDeletingRemoveHistoryAndPreventCacheResurrection() {
        service.execute(user, id, ConversationMode.REACT, "remember");
        service.clear(user, id);
        assertThat(store.getMessages(readId())).isEmpty();
        assertThat(service.history(user, id, null).records()).isEmpty();
        service.delete(user, id);
        assertNotFound(() -> store.getMessages(readId()));
        assertThat(cache).doesNotContainKey(key());
    }

    @Test
    void historyHasStableCursorAndChronologicalOrder() {
        var lease = persistence.claim(user, id, Duration.ofMinutes(3));
        try {
            var memory = MessageWindowChatMemory.builder().id(lease).maxMessages(20).chatMemoryStore(store).build();
            for (int index = 0; index < 55; index++) {
                persistence.complete(lease, ConversationMode.REACT, "goal-" + index,
                        new ReactRunVO("run", "FAILED", null, "test", 0, 0, 0, List.of()), memory);
            }
            var latest = service.history(user, id, null);
            assertThat(latest.records()).hasSize(50);
            assertThat(latest.records().get(0).goal()).isEqualTo("goal-5");
            var earlier = service.history(user, id, latest.nextBeforeId());
            assertThat(earlier.records()).hasSize(5);
            assertThat(earlier.records().get(0).goal()).isEqualTo("goal-0");
            assertThat(earlier.nextBeforeId()).isNull();
        } finally { persistence.release(lease); }
    }

    @Test
    void conversationHttpUsesTokenIdentityAndRequiresLogin() throws Exception {
        mvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized());
        when(sessions.resolve("valid")).thenReturn(new UserPrincipal(user, "memory", "USER"));
        String created = mvc.perform(post("/api/conversations").header("Authorization", "Bearer valid")
                .contentType("application/json").content("{\"title\":\"http\",\"userId\":" + other + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String conversationId = new ObjectMapper().readTree(created).path("data").path("id").asText();
        assertNotFound(() -> service.history(other, conversationId, null));
        mvc.perform(post("/api/react/runs").header("Authorization", "Bearer valid")
                .contentType("application/json")
                .content("{\"goal\":\"记住小周\",\"conversationId\":\"" + conversationId + "\"}"))
                .andExpect(status().isOk());
        assertThat(service.history(user, conversationId, null).records()).hasSize(1);
    }

    @Test
    void characterBudgetEvictsWholeTurnWithoutDeletingFullHistory() {
        memoryProperties.setMaxCharacters(4000);
        when(gateway.chat(any(ChatRequest.class), any(Duration.class))).thenReturn(response("a".repeat(4500)));
        service.execute(user, id, ConversationMode.REACT, "long-answer");
        assertThat(store.getMessages(readId())).isEmpty();
        assertThat(service.history(user, id, null).records().get(0).answer()).hasSize(4500);
    }

    @Test
    void malformedConversationIdAndTitleAreRejectedAtHttpBoundary() throws Exception {
        when(sessions.resolve("valid")).thenReturn(new UserPrincipal(user, "memory", "USER"));
        mvc.perform(post("/api/react/runs").header("Authorization", "Bearer valid")
                .contentType("application/json").content("{\"goal\":\"test\",\"conversationId\":\"../other\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/conversations").header("Authorization", "Bearer valid")
                .contentType("application/json").content("{\"title\":\"" + "a".repeat(81) + "\"}"))
                .andExpect(status().isBadRequest());
        verify(gateway, never()).chat(any(ChatRequest.class), any(Duration.class));
    }

    @Test
    void renamePersistsWithoutChangingHistoryAndRejectsOtherUsers() throws Exception {
        service.execute(user, id, ConversationMode.REACT, "我叫小周");
        when(sessions.resolve("valid")).thenReturn(new UserPrincipal(user, "memory", "USER"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/conversations/" + id)
                .header("Authorization", "Bearer valid").contentType("application/json")
                .content("{\"title\":\"我的计算任务\"}")).andExpect(status().isOk());
        assertThat(service.list(user).get(0).title()).isEqualTo("我的计算任务");
        assertThat(service.history(user, id, null).records()).hasSize(1);
        assertThatThrownBy(() -> service.rename(other, id, "其他用户标题"))
                .isInstanceOf(BusinessException.class);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/conversations/" + id)
                .header("Authorization", "Bearer valid").contentType("application/json")
                .content("{\"title\":\"   \"}")).andExpect(status().isBadRequest());
    }

    private ConversationMemoryId readId() {
        return new ConversationMemoryId(user, id, null);
    }

    private String key() {
        return "agent:chat-memory:" + user + ":" + id;
    }

    private ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).tokenUsage(new TokenUsage(5, 2, 7)).build();
    }

    private void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo("CONVERSATION_NOT_FOUND"));
    }

    private void assertBusy(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo("CONVERSATION_BUSY"));
    }
}
