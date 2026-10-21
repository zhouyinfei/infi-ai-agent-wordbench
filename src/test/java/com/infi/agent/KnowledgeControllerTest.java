package com.infi.agent;

import com.infi.agent.config.SecurityConfig;
import com.infi.agent.controller.KnowledgeController;
import com.infi.agent.mapper.ConversationMapper;
import com.infi.agent.mapper.ConversationTurnMapper;
import com.infi.agent.mapper.UserMapper;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.service.KnowledgeService;
import com.infi.agent.service.SessionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(KnowledgeController.class)
@Import(SecurityConfig.class)
class KnowledgeControllerTest {
    @MockitoBean
    private com.infi.agent.mapper.KnowledgeDocumentMapper knowledgeDocuments;
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private KnowledgeService service;
    @MockitoBean
    private SessionService sessions;
    @MockitoBean
    private UserMapper users;
    @MockitoBean
    private ConversationMapper conversations;
    @MockitoBean
    private ConversationTurnMapper turns;

    @Test
    void anonymousCannotListOrDownloadDocuments() throws Exception {
        mvc.perform(get("/api/knowledge/documents")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/knowledge/documents/other/content")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void searchUsesBearerIdentityAndRejectsInvalidLimit() throws Exception {
        when(sessions.resolve("valid")).thenReturn(new UserPrincipal(7L, "alice", "USER"));
        when(service.search(7L, "问题", 5)).thenReturn(List.of());
        mvc.perform(post("/api/knowledge/search").header("Authorization", "Bearer valid")
                .contentType("application/json").content("{\"query\":\"问题\",\"limit\":5,\"ownerId\":9}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        verify(service).search(7L, "问题", 5);
        mvc.perform(post("/api/knowledge/search").header("Authorization", "Bearer valid")
                .contentType("application/json").content("{\"query\":\"问题\",\"limit\":11}"))
                .andExpect(status().isBadRequest());
        verifyNoMoreInteractions(service);
    }
}
