package com.infi.agent.service;

import com.infi.agent.entity.UserDO;
import com.infi.agent.security.UserPrincipal;

/** 登录凭证管理，持久化实现由业务服务选择。 */
public interface SessionService {
    /**
     * 为用户创建具有固定有效期的登录凭证。
     *
     * @param user 已查询或创建的用户及其凭证版本
     * @return 返回客户端的原始登录令牌
     */
    String create(UserDO user);
    /**
     * 解析登录凭证并验证会话及用户状态，无效或失效凭证返回 null。
     *
     * @param token 客户端登录令牌
     * @return 有效用户身份；凭证无效时为 null
     */
    UserPrincipal resolve(String token);
    /**
     * 撤销指定登录凭证，使该会话无法继续使用。
     *
     * @param token 客户端登录令牌
     */
    void revoke(String token);
    /**
     * 检查账号和来源地址的登录尝试次数，超过限制时抛出业务异常。
     *
     * @param username 用户名
     * @param remoteAddress 登录请求的来源地址
     */
    void checkLoginLimit(String username, String remoteAddress);
}
