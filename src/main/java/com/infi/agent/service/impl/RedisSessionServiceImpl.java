package com.infi.agent.service.impl;

import com.infi.agent.common.BusinessException;
import com.infi.agent.config.AuthProperties;
import com.infi.agent.entity.UserDO;
import com.infi.agent.mapper.UserMapper;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.service.SessionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Redis 保存令牌摘要，固定有效期；每次请求重新校验用户状态和凭证版本。 */
@Service
@RequiredArgsConstructor
public class RedisSessionServiceImpl implements SessionService {
    private static final String SESSION_PREFIX = "agent:session:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<Long> RATE_SCRIPT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]) end; return n",
            Long.class);
    private final StringRedisTemplate redisTemplate;
    private final UserMapper userMapper;
    private final AuthProperties properties;

    /**
     * 生成随机令牌，将令牌的 SHA-256 摘要映射到用户 ID 和凭证版本，并设置 Redis 有效期。
     *
     * @param user 已查询或创建的用户及其凭证版本
     * @return 返回客户端的原始登录令牌
     */
    @Override
    public String create(UserDO user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisTemplate.opsForValue().set(SESSION_PREFIX + digest(token),
                user.getId() + ":" + user.getAuthVersion(), properties.getTokenTtl());
        return token;
    }

    /**
     * 检查令牌格式并读取 Redis 会话，再核对数据库用户状态及凭证版本；无效时返回 null。
     *
     * @param token 客户端登录令牌
     * @return 有效用户身份；凭证无效时为 null
     */
    @Override
    public UserPrincipal resolve(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            return null;
        }
        String value = redisTemplate.opsForValue().get(SESSION_PREFIX + digest(token));
        if (value == null) {
            return null;
        }
        String[] parts = value.split(":");
        UserDO user = userMapper.selectById(Long.valueOf(parts[0]));
        if (user == null || user.getStatus() != 1
                || !user.getAuthVersion().equals(Long.valueOf(parts[1]))) {
            return null;
        }
        return new UserPrincipal(user.getId(), user.getUsername(), user.getRole());
    }

    /**
     * 删除令牌摘要对应的 Redis 会话，立即撤销该登录凭证。
     *
     * @param token 客户端登录令牌
     */
    @Override
    public void revoke(String token) {
        redisTemplate.delete(SESSION_PREFIX + digest(token));
    }

    /**
     * 分别检查用户名和来源地址对应的 Redis 登录限流桶。
     *
     * @param username 用户名
     * @param remoteAddress 登录请求的来源地址
     */
    @Override
    public void checkLoginLimit(String username, String remoteAddress) {
        checkBucket("agent:login:user:" + digest(username));
        checkBucket("agent:login:ip:" + digest(remoteAddress));
    }

    /**
     * 通过 Lua 原子递增登录次数并在首次访问时设置窗口有效期，超限时拒绝登录。
     *
     * @param key Redis 登录限流桶键
     */
    private void checkBucket(String key) {
        Long attempts = redisTemplate.execute(RATE_SCRIPT, List.of(key),
                String.valueOf(properties.getLoginWindow().toMillis()));
        if (attempts == null || attempts > properties.getMaxLoginAttempts()) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMIT", "登录尝试过于频繁，请稍后重试");
        }
    }

    /**
     * 使用 UTF-8 编码计算 SHA-256 十六进制摘要，用于 Redis 会话及限流键。
     *
     * @param value 待计算摘要的字符串
     * @return SHA-256 十六进制摘要
     */
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
