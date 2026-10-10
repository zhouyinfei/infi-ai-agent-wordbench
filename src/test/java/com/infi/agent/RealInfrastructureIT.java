package com.infi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.mapper.UserMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 显式执行的真实 MySQL / Redis 验证，使用独立测试库及唯一账号。 */
@SpringBootTest(properties = {
        "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:schema.sql",
        "app.bootstrap.admin-username=", "app.bootstrap.admin-password="
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class RealInfrastructureIT {
    private static final String TEST_ADDRESS = "198.51.100.75";
    private final List<String> usernames = new ArrayList<>();
    private final List<String> tokens = new ArrayList<>();
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("MYSQL_INTEGRATION_URL",
                "jdbc:mysql://127.0.0.1:3306/infi_agent_it?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai"));
    }

    @Test
    void verifiesRealPersistenceExpirationRevocationAndAdminPermission() throws Exception {
        String username = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        usernames.add(username);
        mvc.perform(post("/api/auth/register").contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of("username", username,
                        "password", "Password123!", "nickname", "联调测试"))))
                .andExpect(status().isOk());
        Long id = userMapper.selectByUsername(username).getId();
        String token = login(username, "Password123!");
        String key = "agent:session:" + digest(token);
        assertThat(redisTemplate.getExpire(key)).isBetween(1L, 28800L);
        mvc.perform(get("/api/admin/users").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/users/me/password").header("Authorization", bearer(token))
                .contentType("application/json").content("""
                {"oldPassword":"Password123!","newPassword":"NewPassword123!"}
                """)).andExpect(status().isOk());
        mvc.perform(get("/api/users/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
        token = login(username, "NewPassword123!");
        mvc.perform(post("/api/auth/logout").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        assertThat(redisTemplate.hasKey("agent:session:" + digest(token))).isFalse();
        token = login(username, "NewPassword123!");
        redisTemplate.expire("agent:session:" + digest(token), Duration.ZERO);
        mvc.perform(get("/api/users/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
        // 仅提升本测试唯一账号，验证真实库上的管理员查询权限。
        jdbcTemplate.update("UPDATE sys_user SET role = 'ADMIN' WHERE id = ?", id);
        token = login(username, "NewPassword123!");
        mvc.perform(get("/api/admin/users").param("keyword", username)
                .header("Authorization", bearer(token))).andExpect(status().isOk());
        String member = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        usernames.add(member);
        mvc.perform(post("/api/admin/users").header("Authorization", bearer(token))
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of("username", member,
                        "password", "Password123!", "nickname", "临时成员"))))
                .andExpect(status().isOk());
        String memberToken = login(member, "Password123!");
        Long memberId = userMapper.selectByUsername(member).getId();
        for (int state : new int[]{0, 1}) {
            mvc.perform(put("/api/admin/users/" + memberId + "/status")
                    .header("Authorization", bearer(token)).contentType("application/json")
                    .content("{\"status\":" + state + "}"))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/users/me").header("Authorization", bearer(memberToken)))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(login(member, "Password123!")).isNotBlank();
    }

    @AfterEach
    void cleanOwnData() throws Exception {
        for (String username : usernames) {
            jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", username);
            redisTemplate.delete("agent:login:user:" + digest(username));
        }
        for (String token : tokens) {
            redisTemplate.delete("agent:session:" + digest(token));
        }
        redisTemplate.delete("agent:login:ip:" + digest(TEST_ADDRESS));
    }

    private String login(String username, String password) throws Exception {
        var response = mvc.perform(post("/api/auth/login").with(request -> {
            request.setRemoteAddr(TEST_ADDRESS);
            return request;
        }).contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse();
        String token = objectMapper.readTree(response.getContentAsString()).path("data").path("token").asText();
        tokens.add(token);
        return token;
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
