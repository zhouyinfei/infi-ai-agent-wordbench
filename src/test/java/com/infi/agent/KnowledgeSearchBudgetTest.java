package com.infi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.common.BusinessException;
import com.infi.agent.react.tool.KnowledgeSearchTool;
import com.infi.agent.react.tool.ReactToolRegistry;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.service.KnowledgeService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证缓存隔离、真实检索上限与不可重试故障的任务内熔断。 */
class KnowledgeSearchBudgetTest {
    private KnowledgeService service;
    private ReactToolRegistry registry;

    @BeforeEach
    void setUp() {
        service = mock(KnowledgeService.class);
        when(service.search(anyLong(), anyString(), eq(5))).thenReturn(List.of());
        registry = new ReactToolRegistry(List.of(new KnowledgeSearchTool(service)), new ObjectMapper());
        authenticate(7L);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void normalizesWhitespaceAndReusesEvenEmptyResults() {
        var scope = registry.newKnowledgeScope();
        assertThat(registry.execute(call("  产品  功能  "), scope).success()).isTrue();
        var reused = registry.execute(call("产品 功能"), scope);
        assertThat(reused.execution()).isEqualTo("CACHED");
        assertThat(reused.result()).contains("未找到资料");
        verify(service, times(1)).search(eq(7L), anyString(), eq(5));
    }

    @Test
    void blocksFourthDistinctQueryAndRemovesSpecification() {
        var scope = registry.newKnowledgeScope();
        for (int index = 0; index < 3; index++) {
            assertThat(registry.execute(call("问题" + index), scope).success()).isTrue();
        }
        assertThat(registry.execute(call("问题4"), scope).execution()).isEqualTo("BLOCKED");
        assertThat(registry.specifications(scope)).isEmpty();
        assertThat(registry.execute(call("问题0"), scope).execution()).isEqualTo("CACHED");
        verify(service, times(3)).search(eq(7L), anyString(), eq(5));
    }

    @Test
    void permanentFailureBlocksDifferentQueriesWithoutCallingBackendAgain() {
        when(service.search(anyLong(), anyString(), eq(5))).thenThrow(new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE, "RAG_NON_RETRYABLE", "Embedding 服务额度不足"));
        var scope = registry.newKnowledgeScope();
        assertThat(registry.execute(call("问题1"), scope).execution()).isEqualTo("NON_RETRYABLE");
        var blocked = registry.execute(call("换词问题"), scope);
        assertThat(blocked.execution()).isEqualTo("BLOCKED");
        assertThat(blocked.result()).contains("额度不足", "停止检索");
        assertThat(registry.specifications(scope)).isEmpty();
        verify(service, times(1)).search(eq(7L), anyString(), eq(5));
    }

    @Test
    void transientFailureCanRecoverWithinBudget() {
        when(service.search(7L, "问题", 5)).thenThrow(new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE, "RAG_CONNECTION_FAILED", "服务暂不可用")).thenReturn(List.of());
        var scope = registry.newKnowledgeScope();
        assertThat(registry.execute(call("问题"), scope).success()).isFalse();
        assertThat(registry.execute(call("问题"), scope).success()).isTrue();
        assertThat(registry.execute(call("问题"), scope).execution()).isEqualTo("CACHED");
        verify(service, times(2)).search(7L, "问题", 5);
    }

    @Test
    void newTasksAndOtherUsersNeverReusePreviousUsersResult() {
        var scope = registry.newKnowledgeScope();
        registry.execute(call("问题"), scope);
        registry.execute(call("问题"), registry.newKnowledgeScope());
        authenticate(8L);
        assertThat(registry.execute(call("问题"), scope).execution()).isEqualTo("EXECUTED");
        verify(service, times(2)).search(7L, "问题", 5);
        verify(service).search(8L, "问题", 5);
    }

    @Test
    void invalidArgumentsDoNotConsumeSearchBudget() {
        var scope = registry.newKnowledgeScope();
        assertThat(registry.execute(call("  "), scope).success()).isFalse();
        assertThat(scope.available()).isTrue();
        verifyNoInteractions(service);
    }

    @Test
    void deselectedKnowledgeIsNotAdvertisedAndCannotBeForcedByModel() {
        var scope = registry.newKnowledgeScope(false);
        assertThat(registry.specifications(scope)).isEmpty();
        var blocked = registry.execute(call("强制检索"), scope);
        assertThat(blocked.execution()).isEqualTo("BLOCKED");
        assertThat(blocked.result()).contains("未勾选知识库");
        verifyNoInteractions(service);
    }

    private static ToolExecutionRequest call(String query) {
        return ToolExecutionRequest.builder().id("call").name("knowledge_search")
                .arguments("{\"query\":\"" + query + "\"}").build();
    }

    private static void authenticate(Long id) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(id, "user", "USER"), null, List.of()));
    }
}
