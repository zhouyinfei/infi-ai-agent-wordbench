package com.infi.agent.service;

import com.infi.agent.entity.UserDO;
import com.infi.agent.security.UserPrincipal;

/** 登录凭证管理，持久化实现由业务服务选择。 */
public interface SessionService {
    String create(UserDO user);
    UserPrincipal resolve(String token);
    void revoke(String token);
    void checkLoginLimit(String username, String remoteAddress);
}
