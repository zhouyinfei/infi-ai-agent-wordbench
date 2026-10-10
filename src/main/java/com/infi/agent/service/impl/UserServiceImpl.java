package com.infi.agent.service.impl;

import com.infi.agent.common.BusinessException;
import com.infi.agent.config.AuthProperties;
import com.infi.agent.dto.LoginDTO;
import com.infi.agent.dto.PasswordDTO;
import com.infi.agent.dto.ProfileDTO;
import com.infi.agent.dto.RegisterDTO;
import com.infi.agent.entity.UserDO;
import com.infi.agent.mapper.UserMapper;
import com.infi.agent.service.SessionService;
import com.infi.agent.service.UserService;
import com.infi.agent.vo.LoginVO;
import com.infi.agent.vo.PageVO;
import com.infi.agent.vo.UserVO;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户业务实现，权限角色不从注册输入中读取。 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private static final String USER_ROLE = "USER";
    private static final String ADMIN_ROLE = "ADMIN";
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessionService;
    private final AuthProperties authProperties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO register(RegisterDTO request) {
        UserDO user = createUser(request.username(), request.password(), request.nickname(), request.email(), USER_ROLE);
        return UserVO.from(requireUser(user.getId()));
    }

    @Override
    public LoginVO login(LoginDTO request, String remoteAddress) {
        String username = normalizeUsername(request.username());
        sessionService.checkLoginLimit(username, remoteAddress);
        UserDO user = userMapper.selectByUsername(username);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())
                || user.getStatus() != 1) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误，或账号已禁用");
        }
        return new LoginVO(sessionService.create(user), authProperties.getTokenTtl().toSeconds(), UserVO.from(user));
    }

    @Override
    public UserVO getProfile(Long userId) {
        return UserVO.from(requireUser(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateProfile(Long userId, ProfileDTO request) {
        requireUser(userId);
        userMapper.updateProfile(userId, request.nickname().trim(), normalizeEmail(request.email()));
        return getProfile(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(Long userId, PasswordDTO request) {
        UserDO user = requireUser(userId);
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "WRONG_PASSWORD", "原密码不正确");
        }
        if (userMapper.updatePassword(userId, user.getPasswordHash(),
                passwordEncoder.encode(request.newPassword())) != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "PASSWORD_CHANGED", "密码已发生变化，请重新登录");
        }
    }

    @Override
    public PageVO<UserVO> listUsers(int page, int size, String keyword) {
        if (page < 1 || page > 100000 || size < 1 || size > 100 || (keyword != null && keyword.length() > 50)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "分页或搜索参数不合法");
        }
        return new PageVO<>(userMapper.selectPage(keyword, (page - 1) * size, size)
                .stream().map(UserVO::from).toList(), userMapper.count(keyword), page, size);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long userId, Integer status) {
        UserDO user = requireUser(userId);
        if (ADMIN_ROLE.equals(user.getRole())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ADMIN_PROTECTED", "本阶段不允许禁用管理员账号");
        }
        userMapper.updateStatus(userId, status);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bootstrapAdmin(String username, String password) {
        if (userMapper.count(null) != 0) {
            return;
        }
        if (!username.matches("^[a-zA-Z][a-zA-Z0-9_]{3,31}$")
                || !password.matches("^[\\x21-\\x7E]{8,64}$")) {
            throw new IllegalArgumentException("管理员初始化配置不符合账号密码规则");
        }
        createUser(username, password, "系统管理员", null, ADMIN_ROLE);
    }

    private UserDO createUser(String username, String password, String nickname, String email, String role) {
        UserDO user = new UserDO();
        user.setUsername(normalizeUsername(username));
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setNickname(nickname.trim());
        user.setEmail(normalizeEmail(email));
        user.setRole(role);
        user.setStatus(1);
        user.setAuthVersion(0L);
        userMapper.insert(user);
        return user;
    }

    private UserDO requireUser(Long id) {
        UserDO user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在");
        }
        return user;
    }

    private static String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim();
    }
}
