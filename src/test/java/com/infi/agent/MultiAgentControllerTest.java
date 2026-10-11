package com.infi.agent;

import com.infi.agent.config.SecurityConfig;
import com.infi.agent.controller.MultiAgentController;
import com.infi.agent.dto.MultiAgentRunDTO;
import com.infi.agent.mapper.UserMapper;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.service.MultiAgentService;
import com.infi.agent.service.SessionService;
import com.infi.agent.vo.ReactRunVO;
import java.util.List;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证已登录用户权限与 HTTP 输入校验。 */
@WebMvcTest(MultiAgentController.class)
@Import(SecurityConfig.class)
class MultiAgentControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private MultiAgentService multiAgentService;
    @MockitoBean
    private SessionService sessionService;
    @MockitoBean
    private UserMapper userMapper;

    @Test
    void anonymousCannotReadConfiguration() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/multi-agent/config"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousUserCannotExecute() throws Exception {
        mvc.perform(post("/api/multi-agent/runs").contentType("application/json").content("{\"goal\":\"计算\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(multiAgentService);
    }

    @Test
    void normalUserCanExecuteAndIdentityComesFromToken() throws Exception {
        when(sessionService.resolve("valid")).thenReturn(new UserPrincipal(7L, "alice", "USER"));
        when(multiAgentService.execute(eq(7L), any(MultiAgentRunDTO.class)))
                .thenReturn(new ReactRunVO("run", "SUCCEEDED", "42", null, 2, 1, 5, List.of()));
        mvc.perform(post("/api/multi-agent/runs").header("Authorization", "Bearer valid")
                .contentType("application/json").content("{\"goal\":\"计算\",\"userId\":999}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.answer").value("42"));
        verify(multiAgentService).execute(eq(7L), any(MultiAgentRunDTO.class));
    }

    @Test
    void invalidGoalsAreRejected() throws Exception {
        when(sessionService.resolve("valid")).thenReturn(new UserPrincipal(7L, "alice", "USER"));
        for (String input : List.of("{\"goal\":\" \"}", "{}", 
                "{\"goal\":\"" + "a".repeat(2001) + "\"}")) {
            mvc.perform(post("/api/multi-agent/runs").header("Authorization", "Bearer valid")
                    .contentType("application/json").content(input)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(multiAgentService);
    }
}

