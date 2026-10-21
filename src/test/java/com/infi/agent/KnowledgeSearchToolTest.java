package com.infi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.react.tool.KnowledgeSearchTool;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.service.KnowledgeService;
import com.infi.agent.vo.KnowledgeHitVO;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeSearchToolTest {
    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void identityComesFromAuthenticationAndResultPreservesSource() throws Exception {
        KnowledgeService service = mock(KnowledgeService.class);
        when(service.search(7L, "产品功能", 5)).thenReturn(List.of(
                new KnowledgeHitVO("source-id", "产品.md", 3, "知识库检索", 0.8)));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(7L, "alice", "USER"), null, List.of()));
        var tool = new KnowledgeSearchTool(service);
        assertThat(tool.execute(new ObjectMapper().readTree("{\"query\":\"产品功能\"}")))
                .contains("[产品.md#3]", "source-id", "知识库检索");
        assertThatThrownBy(() -> tool.execute(new ObjectMapper().readTree("{\"query\":\"产品功能\",\"ownerId\":9}")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(service, times(1)).search(7L, "产品功能", 5);
    }

    @Test
    void missingAuthenticationFailsClosed() {
        KnowledgeService service = mock(KnowledgeService.class);
        assertThatThrownBy(() -> new KnowledgeSearchTool(service).search("问题"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
    }
}
