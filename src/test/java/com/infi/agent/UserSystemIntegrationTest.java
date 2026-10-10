package com.infi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.mapper.UserMapper;
import com.infi.agent.service.UserService;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP + 实际业务服务 + MyBatis SQL 集成测试，仅替换外部 Redis 传输。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserSystemIntegrationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @MockitoBean
    private StringRedisTemplate redisTemplate;
    private Map<String, String> sessions;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate.update("DELETE FROM sys_user");
        sessions = new HashMap<>();
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(operations);
        doAnswer(invocation -> {
            sessions.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(operations).set(anyString(), anyString(), any(Duration.class));
        when(operations.get(anyString())).thenAnswer(invocation -> sessions.get(invocation.getArgument(0)));
        when(redisTemplate.delete(anyString())).thenAnswer(invocation ->
                sessions.remove(invocation.getArgument(0)) != null);
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), any(), any(Object[].class));
    }

    @Test
    void registerHashesPasswordAndNeverAcceptsAdminRole() throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json").content("""
                {"username":"Alice","password":"Password123!","nickname":"Alice","role":"ADMIN"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        var user = userMapper.selectByUsername("alice");
        assertThat(user.getPasswordHash()).isNotEqualTo("Password123!");
        assertThat(passwordEncoder.matches("Password123!", user.getPasswordHash())).isTrue();
        register("ALICE", 409);
    }

    @Test
    void rejectsInvalidRegistrationAndWrongCredentials() throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json").content("""
                {"username":"abc","password":"short","nickname":"Alice"}
                """)).andExpect(status().isBadRequest());
        register("alice", 200);
        mvc.perform(post("/api/auth/login").contentType("application/json").content("""
                {"username":"alice","password":"WrongPassword"}
                """)).andExpect(status().isUnauthorized());
    }

    @Test
    void enforcesAuthenticationAndAdminBoundary() throws Exception {
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        register("alice", 200);
        String token = login("alice");
        mvc.perform(get("/api/admin/users").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/users/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("alice"));
        mvc.perform(put("/api/users/me").header("Authorization", bearer(token))
                .contentType("application/json").content("""
                {"nickname":"新昵称","email":"alice@example.com","role":"ADMIN","id":999}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    void logoutRevokesTokenAndStoresOnlyDigest() throws Exception {
        register("alice", 200);
        String token = login("alice");
        assertThat(sessions.keySet()).allMatch(key -> !key.contains(token));
        mvc.perform(post("/api/auth/logout").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordChangeInvalidatesEveryExistingToken() throws Exception {
        register("alice", 200);
        String first = login("alice");
        String second = login("alice");
        mvc.perform(put("/api/users/me/password").header("Authorization", bearer(first))
                .contentType("application/json").content("""
                {"oldPassword":"WrongPassword","newPassword":"NewPassword123!"}
                """)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/users/me/password").header("Authorization", bearer(first))
                .contentType("application/json").content("""
                {"oldPassword":"Password123!","newPassword":"NewPassword123!"}
                """)).andExpect(status().isOk());
        for (String token : new String[]{first, second}) {
            mvc.perform(get("/api/users/me").header("Authorization", bearer(token)))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType("application/json").content("""
                {"username":"alice","password":"NewPassword123!"}
                """)).andExpect(status().isOk());
    }

    @Test
    void adminCanCreatePageDisableAndEnableUserWithoutRevivingOldToken() throws Exception {
        userService.bootstrapAdmin("admin", "Password123!");
        String admin = login("admin");
        mvc.perform(post("/api/admin/users").header("Authorization", bearer(admin))
                .contentType("application/json").content("""
                {"username":"alice","password":"Password123!","nickname":"Alice"}
                """)).andExpect(status().isOk());
        String token = login("alice");
        Long id = userMapper.selectByUsername("alice").getId();
        mvc.perform(get("/api/admin/users").param("keyword", "alice").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].passwordHash").doesNotExist());
        for (int state : new int[]{0, 1}) {
            mvc.perform(put("/api/admin/users/" + id + "/status").header("Authorization", bearer(admin))
                    .contentType("application/json").content("{\"status\":" + state + "}"))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/users/me").header("Authorization", bearer(token)))
                    .andExpect(status().isUnauthorized());
            if (state == 0) {
                mvc.perform(post("/api/auth/login").contentType("application/json").content("""
                        {"username":"alice","password":"Password123!"}
                        """)).andExpect(status().isUnauthorized());
            }
        }
        assertThat(login("alice")).isNotBlank();
        Long adminId = userMapper.selectByUsername("admin").getId();
        mvc.perform(put("/api/admin/users/" + adminId + "/status").header("Authorization", bearer(admin))
                .contentType("application/json").content("{\"status\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/users").param("size", "101").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/users").param("page", "invalid").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rateLimitStopsLoginBeforeIssuingToken() throws Exception {
        register("alice", 200);
        doReturn(11L).when(redisTemplate).execute(any(RedisScript.class), any(), any(Object[].class));
        mvc.perform(post("/api/auth/login").contentType("application/json").content("""
                {"username":"alice","password":"Password123!"}
                """)).andExpect(status().isTooManyRequests());
        assertThat(sessions).isEmpty();
    }

    @Test
    void malformedTokenCannotAuthenticate() throws Exception {
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer arbitrary"))
                .andExpect(status().isUnauthorized());
    }

    private void register(String username, int expectedStatus) throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of("username", username,
                        "password", "Password123!", "nickname", "Alice"))))
                .andExpect(status().is(expectedStatus));
    }

    private String login(String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of("username", username, "password", "Password123!"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("data").path("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
