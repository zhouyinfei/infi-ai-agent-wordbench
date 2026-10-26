package com.infi.agent;

import com.infi.agent.config.SecurityConfig;
import com.infi.agent.controller.AgentTaskController;
import com.infi.agent.dto.AgentTaskDecisionDTO;
import com.infi.agent.dto.AgentTaskSubmitDTO;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.service.AgentTaskService;
import com.infi.agent.service.SessionService;
import com.infi.agent.vo.AgentTaskVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 工作台任务HTTP身份、普通用户确认与参数校验。 */
@WebMvcTest(AgentTaskController.class)
@Import(SecurityConfig.class)
@MockitoBean(types = {com.infi.agent.mapper.UserMapper.class, com.infi.agent.mapper.ConversationMapper.class,
        com.infi.agent.mapper.ConversationTurnMapper.class, com.infi.agent.mapper.KnowledgeDocumentMapper.class})
class AgentTaskControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private AgentTaskService tasks;
    @MockitoBean
    private SessionService sessions;

    @Test
    void anonymousCannotInspectOrDecideTasks() throws Exception {
        mvc.perform(get("/api/agent-tasks")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/agent-tasks/task/decision").contentType("application/json")
                .content("{\"approvalId\":\"approval\",\"approved\":true}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(tasks);
    }

    @Test
    void normalUserCanSubmitAndDecideUsingAuthenticatedIdentity() throws Exception {
        var user = new UserPrincipal(7L, "normal-user", "USER");
        when(sessions.resolve("token")).thenReturn(user);
        when(tasks.submit(eq(user), any(AgentTaskSubmitDTO.class)))
                .thenReturn(new AgentTaskVO("task", null, com.infi.agent.memory.ConversationMode.REACT, "任务", "RUNNING", null, null));
        when(tasks.decide(eq(user), eq("task"), any(AgentTaskDecisionDTO.class)))
                .thenReturn(new AgentTaskVO("task", null, com.infi.agent.memory.ConversationMode.REACT, "任务", "RUNNING", null, null));
        mvc.perform(post("/api/agent-tasks").header("Authorization", "Bearer token")
                .contentType("application/json").content("{\"goal\":\"任务\",\"mode\":\"REACT\",\"userId\":999}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("task"));
        mvc.perform(post("/api/agent-tasks/task/decision").header("Authorization", "Bearer token")
                .contentType("application/json").content("{\"approvalId\":\"approval\",\"approved\":false,\"userId\":999}"))
                .andExpect(status().isOk());
        verify(tasks).decide(user, "task", new AgentTaskDecisionDTO("approval", false));
    }

    @Test
    void missingApprovalIdOrDecisionAndInvalidGoalAreRejected() throws Exception {
        when(sessions.resolve("token")).thenReturn(new UserPrincipal(7L, "normal-user", "USER"));
        mvc.perform(post("/api/agent-tasks").header("Authorization", "Bearer token")
                .contentType("application/json").content("{\"goal\":\" \",\"mode\":\"REACT\"}"))
                .andExpect(status().isBadRequest());
        for (String body : new String[]{"{}", "{\"approvalId\":\"approval\"}", "{\"approved\":true}"}) {
            mvc.perform(post("/api/agent-tasks/task/decision").header("Authorization", "Bearer token")
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(tasks);
    }
}
