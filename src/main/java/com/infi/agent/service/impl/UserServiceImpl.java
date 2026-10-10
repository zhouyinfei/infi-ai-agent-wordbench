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

    /**
     * 以普通用户角色创建账号并重新查询资料，事务失败时回滚。
     *
     * @param request 已通过接口参数校验的请求数据
     * @return 操作结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO register(RegisterDTO request) {
        UserDO user = createUser(request.username(), request.password(), request.nickname(), request.email(), USER_ROLE);
        return UserVO.from(requireUser(user.getId()));
    }

    /**
     * 规范化用户名并检查登录频率，验证密码和账号状态后签发登录凭证。
     *
     * @param request 已通过接口参数校验的请求数据
     * @param remoteAddress 登录请求的来源地址
     * @return 登录令牌、有效期和用户资料
     */
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

    /**
     * 查询用户并转换为不含密码散列的资料对象。
     *
     * @param userId 目标用户 ID；当前用户接口从认证上下文获取
     * @return 操作结果
     */
    @Override
    public UserVO getProfile(Long userId) {
        return UserVO.from(requireUser(userId));
    }

    /**
     * 确认用户存在后更新昵称和邮箱，在同一事务中返回最新资料。
     *
     * @param userId 目标用户 ID；当前用户接口从认证上下文获取
     * @param request 已通过接口参数校验的请求数据
     * @return 操作结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateProfile(Long userId, ProfileDTO request) {
        requireUser(userId);
        userMapper.updateProfile(userId, request.nickname().trim(), normalizeEmail(request.email()));
        return getProfile(userId);
    }

    /**
     * 校验原密码，通过带旧密码条件的更新防止并发覆盖，并使旧凭证版本失效。
     *
     * @param userId 目标用户 ID；当前用户接口从认证上下文获取
     * @param request 已通过接口参数校验的请求数据
     */
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

    /**
     * 校验页码、页大小和关键字长度，查询用户分页结果及总数。
     *
     * @param page 从 1 开始的页码
     * @param size 每页记录数
     * @param keyword 可选的用户搜索关键字
     * @return 用户分页记录和总数
     */
    @Override
    public PageVO<UserVO> listUsers(int page, int size, String keyword) {
        if (page < 1 || page > 100000 || size < 1 || size > 100 || (keyword != null && keyword.length() > 50)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "分页或搜索参数不合法");
        }
        return new PageVO<>(userMapper.selectPage(keyword, (page - 1) * size, size)
                .stream().map(UserVO::from).toList(), userMapper.count(keyword), page, size);
    }

    /**
     * 确认用户存在并保护管理员账号，然后更新普通用户的启用状态。
     *
     * @param userId 目标用户 ID；当前用户接口从认证上下文获取
     * @param status 用户状态，1 为启用、0 为禁用
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long userId, Integer status) {
        UserDO user = requireUser(userId);
        if (ADMIN_ROLE.equals(user.getRole())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ADMIN_PROTECTED", "本阶段不允许禁用管理员账号");
        }
        userMapper.updateStatus(userId, status);
    }

    /**
     * 用户表为空时校验初始化账号密码并创建管理员；已有用户时直接返回。
     *
     * @param username 用户名
     * @param password 明文密码，仅用于加密或初始化校验
     */
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

    /**
     * 规范化账号资料、加密密码并设置角色与初始凭证版本，将用户写入数据库。
     *
     * @param username 用户名
     * @param password 明文密码，仅用于加密或初始化校验
     * @param nickname 用户昵称
     * @param email 可选邮箱
     * @param role 由服务端指定的用户角色
     * @return 用户数据库对象
     */
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

    /**
     * 按 ID 查询用户，不存在时抛出 USER_NOT_FOUND 业务异常。
     *
     * @param id 待查询或更新的用户 ID
     * @return 用户数据库对象
     */
    private UserDO requireUser(Long id) {
        UserDO user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在");
        }
        return user;
    }

    /**
     * 去除用户名首尾空白并使用固定区域规则转换为小写。
     *
     * @param username 用户名
     * @return 规范化用户名
     */
    private static String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 去除邮箱首尾空白，未填写或仅有空白时返回 null。
     *
     * @param email 可选邮箱
     * @return 规范化邮箱或 null
     */
    private static String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim();
    }
}
