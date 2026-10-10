package com.infi.agent;

import com.infi.agent.config.SecurityConfig;
import com.infi.agent.controller.ReactController;
import com.infi.agent.dto.ReactRunDTO;
import com.infi.agent.mapper.UserMapper;
import com.infi.agent.security.UserPrincipal;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证已登录用户权限与 HTTP 输入校验。 */
@WebMvcTest(ReactController.class)
@Import(SecurityConfig.class)
class ReactControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ReactService reactService;
    @MockitoBean
    private SessionService sessionService;
    @MockitoBean
    private UserMapper userMapper;

    @Test
    void anonymousUserCannotReadConfigurationOrExecute() throws Exception {
        mvc.perform(get("/api/react/config")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/react/runs").contentType("application/json").content("{\"goal\":\"计算\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reactService);
    }

    @Test
    void normalUserCanExecuteAndIdentityComesFromToken() throws Exception {
        when(sessionService.resolve("valid")).thenReturn(new UserPrincipal(7L, "alice", "USER"));
        when(reactService.execute(eq(7L), any(ReactRunDTO.class)))
                .thenReturn(new ReactRunVO("run", "SUCCEEDED", "42", null, 2, 1, 5, List.of()));
        mvc.perform(post("/api/react/runs").header("Authorization", "Bearer valid")
                .contentType("application/json").content("{\"goal\":\"计算\",\"userId\":999}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.answer").value("42"));
        verify(reactService).execute(eq(7L), any(ReactRunDTO.class));
    }

    @Test
    void invalidGoalsAreRejected() throws Exception {
        when(sessionService.resolve("valid")).thenReturn(new UserPrincipal(7L, "alice", "USER"));
        for (String input : List.of("{\"goal\":\" \"}", "{}", 
                "{\"goal\":\"" + "a".repeat(2001) + "\"}")) {
            mvc.perform(post("/api/react/runs").header("Authorization", "Bearer valid")
                    .contentType("application/json").content(input)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(reactService);
    }
}
