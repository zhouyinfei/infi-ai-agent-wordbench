package com.infi.agent.service;

import com.infi.agent.dto.LoginDTO;
import com.infi.agent.dto.PasswordDTO;
import com.infi.agent.dto.ProfileDTO;
import com.infi.agent.dto.RegisterDTO;
import com.infi.agent.vo.LoginVO;
import com.infi.agent.vo.PageVO;
import com.infi.agent.vo.UserVO;

/** 用户业务边界。 */
public interface UserService {
    /**
     * 创建角色为 USER 的普通用户，返回不含密码的用户资料。
     *
     * @param request 已通过接口参数校验的请求数据
     * @return 操作结果
     */
    UserVO register(RegisterDTO request);
    /**
     * 执行登录限流、账号密码和启用状态校验，成功后创建会话。
     *
     * @param request 已通过接口参数校验的请求数据
     * @param remoteAddress 登录请求的来源地址
     * @return 登录令牌、有效期和用户资料
     */
    LoginVO login(LoginDTO request, String remoteAddress);
    /**
     * 查询指定用户资料，用户不存在时抛出业务异常。
     *
     * @param userId 目标用户 ID；当前用户接口从认证上下文获取
     * @return 操作结果
     */
    UserVO getProfile(Long userId);
    /**
     * 更新指定用户昵称与邮箱并返回最新资料。
     *
     * @param userId 目标用户 ID；当前用户接口从认证上下文获取
     * @param request 已通过接口参数校验的请求数据
     * @return 操作结果
     */
    UserVO updateProfile(Long userId, ProfileDTO request);
    /**
     * 验证原密码并更新密码与凭证版本，使旧会话失效。
     *
     * @param userId 目标用户 ID；当前用户接口从认证上下文获取
     * @param request 已通过接口参数校验的请求数据
     */
    void changePassword(Long userId, PasswordDTO request);
    /**
     * 校验分页与搜索参数并查询用户列表及总数。
     *
     * @param page 从 1 开始的页码
     * @param size 每页记录数
     * @param keyword 可选的用户搜索关键字
     * @return 用户分页记录和总数
     */
    PageVO<UserVO> listUsers(int page, int size, String keyword);
    /**
     * 更新普通用户的启用状态，禁止禁用管理员账号。
     *
     * @param userId 目标用户 ID；当前用户接口从认证上下文获取
     * @param status 用户状态，1 为启用、0 为禁用
     */
    void updateStatus(Long userId, Integer status);
    /**
     * 仅在用户表为空时，使用初始化配置创建首个管理员。
     *
     * @param username 用户名
     * @param password 明文密码，仅用于加密或初始化校验
     */
    void bootstrapAdmin(String username, String password);
}
