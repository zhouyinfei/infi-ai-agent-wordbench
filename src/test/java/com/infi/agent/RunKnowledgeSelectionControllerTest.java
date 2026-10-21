package com.infi.agent;

import com.infi.agent.config.SecurityConfig;
import com.infi.agent.controller.MultiAgentController;
import com.infi.agent.controller.PlanExecuteController;
import com.infi.agent.controller.ReactController;
import com.infi.agent.dto.MultiAgentRunDTO;
import com.infi.agent.dto.PlanExecuteRunDTO;
import com.infi.agent.dto.ReactRunDTO;
import com.infi.agent.mapper.ConversationMapper;
import com.infi.agent.mapper.ConversationTurnMapper;
import com.infi.agent.mapper.KnowledgeDocumentMapper;
import com.infi.agent.mapper.UserMapper;
import com.infi.agent.memory.ConversationMode;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.service.ConversationService;
import com.infi.agent.service.MultiAgentService;
import com.infi.agent.service.PlanExecuteService;
import com.infi.agent.service.ReactService;
import com.infi.agent.service.SessionService;
import com.infi.agent.vo.ReactRunVO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证三种接口对显式授权和会话记忆转发均不丢失用户选择。 */
@WebMvcTest({ReactController.class, PlanExecuteController.class, MultiAgentController.class})
@Import(SecurityConfig.class)
class RunKnowledgeSelectionControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private SessionService sessions;
    @MockitoBean private ReactService react;
    @MockitoBean private PlanExecuteService plan;
    @MockitoBean private MultiAgentService multi;
    @MockitoBean private ConversationService conversations;
    @MockitoBean private UserMapper users;
    @MockitoBean private ConversationMapper conversationMapper;
    @MockitoBean private ConversationTurnMapper turns;
    @MockitoBean private KnowledgeDocumentMapper documents;

    @Test
    void omittedSelectionDefaultsToFalseAndExplicitSelectionReachesAllModes() throws Exception {
        when(sessions.resolve("valid")).thenReturn(new UserPrincipal(7L, "alice", "USER"));
        var result = new ReactRunVO("run", "SUCCEEDED", "回答", null, 1, 0, 1, List.of());
        when(react.execute(eq(7L), any(ReactRunDTO.class))).thenReturn(result);
        when(plan.execute(eq(7L), any(PlanExecuteRunDTO.class))).thenReturn(result);
        when(multi.execute(eq(7L), any(MultiAgentRunDTO.class))).thenReturn(result);
        for (String route : List.of("react", "plan-execute", "multi-agent")) {
            mvc.perform(post("/api/" + route + "/runs").header("Authorization", "Bearer valid")
                    .contentType("application/json").content("{\"goal\":\"问题\"}")).andExpect(status().isOk());
            mvc.perform(post("/api/" + route + "/runs").header("Authorization", "Bearer valid")
                    .contentType("application/json").content("{\"goal\":\"问题\",\"useKnowledge\":true}"))
                    .andExpect(status().isOk());
        }
        verify(react).execute(eq(7L), argThat(request -> !request.useKnowledge()));
        verify(react).execute(eq(7L), argThat(ReactRunDTO::useKnowledge));
        verify(plan).execute(eq(7L), argThat(request -> !request.useKnowledge()));
        verify(plan).execute(eq(7L), argThat(PlanExecuteRunDTO::useKnowledge));
        verify(multi).execute(eq(7L), argThat(request -> !request.useKnowledge()));
        verify(multi).execute(eq(7L), argThat(MultiAgentRunDTO::useKnowledge));
    }

    @Test
    void conversationRequestsForwardTheSelectionWithoutInheritingPriorTurns() throws Exception {
        when(sessions.resolve("valid")).thenReturn(new UserPrincipal(7L, "alice", "USER"));
        String id = "11111111-1111-1111-1111-111111111111";
        String[] routes = {"react", "plan-execute", "multi-agent"};
        ConversationMode[] modes = {ConversationMode.REACT, ConversationMode.PLAN_EXECUTE, ConversationMode.MULTI_AGENT};
        for (int index = 0; index < routes.length; index++) {
            for (boolean selected : new boolean[]{true, false}) {
                mvc.perform(post("/api/" + routes[index] + "/runs").header("Authorization", "Bearer valid")
                        .contentType("application/json").content("{\"goal\":\"问题\",\"conversationId\":\"" + id
                                + "\",\"useKnowledge\":" + selected + "}")).andExpect(status().isOk());
                verify(conversations).execute(7L, id, modes[index], "问题", selected, false);
            }
        }
        verifyNoInteractions(react, plan, multi);
    }

    @Test
    void conversationRequestsForwardIndependentNetworkSelection() throws Exception {
        when(sessions.resolve("valid")).thenReturn(new UserPrincipal(7L, "alice", "USER"));
        String id = "11111111-1111-1111-1111-111111111111";
        String[] routes = {"react", "plan-execute", "multi-agent"};
        ConversationMode[] modes = {ConversationMode.REACT, ConversationMode.PLAN_EXECUTE, ConversationMode.MULTI_AGENT};
        for (int index = 0; index < routes.length; index++) {
            mvc.perform(post("/api/" + routes[index] + "/runs").header("Authorization", "Bearer valid")
                    .contentType("application/json").content("{\"goal\":\"问题\",\"conversationId\":\"" + id
                            + "\",\"useKnowledge\":false,\"useNetwork\":true}")).andExpect(status().isOk());
            verify(conversations).execute(7L, id, modes[index], "问题", false, true);
        }
    }
}
